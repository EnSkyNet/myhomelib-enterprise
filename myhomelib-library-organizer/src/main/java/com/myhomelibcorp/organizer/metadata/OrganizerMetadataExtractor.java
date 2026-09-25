package com.myhomelibcorp.organizer.metadata;

import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Conservative local metadata reader. It never invokes network providers and never mutates the source. */
@Component
public final class OrganizerMetadataExtractor {
    private static final long MAX_XML_ENTRY_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_METADATA_FIELD_CHARS = 256 * 1024;
    private static final int MAX_ANNOTATION_CHARS = 1024 * 1024;

    public ExtractedBookMetadata extract(Path source, DetectedBookFormat format) {
        List<String> warnings = new ArrayList<>();
        try {
            if (source == null) throw new IllegalArgumentException("Unsafe/non-regular metadata source");
            Path safeSource = OrganizerPathSafety.canonicalExistingFile(source, "Organizer metadata source");
            return switch (format.format()) {
                case "fb2" -> {
                    InputStream raw = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS);
                    yield parseFb2(new BoundedInputStream(raw, MAX_XML_ENTRY_BYTES), format, warnings, safeSource);
                }
                case "fb2_zip" -> parseFb2Zip(safeSource, format, warnings);
                case "epub" -> parseEpub(safeSource, format, warnings);
                case "docx" -> parseDocx(safeSource, format, warnings);
                default -> fallback(safeSource, format, warnings);
            };
        } catch (Exception failure) {
            warnings.add("Metadata extraction failed: " + failure.getClass().getSimpleName());
            return fallback(source, format, warnings);
        }
    }

    private ExtractedBookMetadata parseFb2Zip(Path source, DetectedBookFormat format, List<String> warnings) throws Exception {
        try (ZipFile zip = SafeZipSupport.openDetected(source, "Organizer metadata archive")) {
            validateArchiveForMetadata(zip);
            ZipArchiveEntry selected = null;
            int inspected = 0;
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                if (++inspected > ArchiveSafetyLimits.MAX_ENTRY_COUNT)
                    throw new IllegalArgumentException("FB2.ZIP has too many entries");
                ZipArchiveEntry entry = entries.nextElement();
                if (unsafe(entry.getName())) throw new IllegalArgumentException("Unsafe FB2.ZIP entry path");
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".fb2")) continue;
                if (selected != null) throw new IllegalArgumentException("FB2.ZIP contains multiple FB2 payloads");
                selected = entry;
            }
            if (selected == null) throw new IllegalArgumentException("FB2 payload missing");
            if (selected.getSize() > MAX_XML_ENTRY_BYTES) throw new IllegalArgumentException("FB2 payload is too large for metadata scan");
            long compressed = selected.getCompressedSize(), size = selected.getSize();
            if (compressed > 0 && size > 0 && exceedsCompressionRatio(size, compressed))
                throw new IllegalArgumentException("Suspicious FB2.ZIP compression ratio");
            try (InputStream raw = zip.getInputStream(selected); InputStream in = new BoundedInputStream(raw, MAX_XML_ENTRY_BYTES)) {
                return parseFb2(in, format, warnings, source);
            }
        }
    }

    private ExtractedBookMetadata parseFb2(InputStream source, DetectedBookFormat format, List<String> warnings, Path path) throws Exception {
        try (InputStream in = source) {
            XMLStreamReader r = SecureXmlInputFactory.create(true, false).createXMLStreamReader(in);
            String title = "", series = "", seq = "", lang = "", isbn = "", publisher = "", annotation = "";
            Integer year = null; boolean cover = false; List<String> authors = new ArrayList<>(), genres = new ArrayList<>();
            boolean inTitleInfo = false, inAuthor = false, inAnnotation = false;
            boolean annotationTruncated = false;
            String first = "", middle = "", last = ""; StringBuilder annotationText = new StringBuilder();
            try {
                while (r.hasNext()) {
                    int e = r.next();
                    if (e == XMLStreamConstants.START_ELEMENT) {
                        String local = r.getLocalName();
                        // All metadata used by Organizer lives in FB2 <description>. Do not scan the
                        // potentially huge body payload after metadata has already been collected.
                        if ("body".equals(local)) break;
                        if ("title-info".equals(local)) inTitleInfo = true;
                        else if (inTitleInfo && "author".equals(local)) { inAuthor = true; first = middle = last = ""; }
                        else if (inTitleInfo && "annotation".equals(local)) { inAnnotation = true; annotationText.setLength(0); annotationTruncated = false; }
                        else if (inTitleInfo && "coverpage".equals(local)) cover = true;
                        else if (inTitleInfo && "sequence".equals(local) && series.isBlank()) { series = attr(r,"name"); seq = attr(r,"number"); }
                        else if (inTitleInfo && Set.of("book-title","genre","lang","first-name","middle-name","last-name").contains(local)) {
                            String text = safeElementText(r, warnings, local);
                            switch (local) {
                                case "book-title" -> title = firstNonBlank(title, text);
                                case "genre" -> { if (!text.isBlank()) genres.add(text); }
                                case "lang" -> lang = firstNonBlank(lang, text);
                                case "first-name" -> { if (inAuthor) first = text; }
                                case "middle-name" -> { if (inAuthor) middle = text; }
                                case "last-name" -> { if (inAuthor) last = text; }
                            }
                        } else if ("isbn".equals(local)) isbn = firstNonBlank(isbn, safeElementText(r, warnings, local));
                        else if ("publisher".equals(local)) publisher = firstNonBlank(publisher, safeElementText(r, warnings, local));
                        else if ("year".equals(local)) { String y=safeElementText(r, warnings, local); if (y.matches("\\d{4}")) year=Integer.valueOf(y); }
                    } else if ((e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) && inAnnotation) {
                        if (!appendBounded(annotationText, r.getText(), MAX_ANNOTATION_CHARS)) annotationTruncated = true;
                        if (annotationText.length() < MAX_ANNOTATION_CHARS) annotationText.append(' ');
                    }
                    else if (e == XMLStreamConstants.END_ELEMENT) {
                        String local = r.getLocalName();
                        if ("author".equals(local) && inAuthor) {
                            String name = String.join(" ", List.of(first,middle,last).stream().filter(v -> v != null && !v.isBlank()).toList());
                            if (!name.isBlank()) authors.add(name); inAuthor = false;
                        } else if ("annotation".equals(local) && inAnnotation) {
                            annotation = normalizeWhitespace(annotationText);
                            if (annotationTruncated) warnings.add("FB2 annotation was truncated to the metadata safety limit");
                            inAnnotation=false;
                        }
                        else if ("title-info".equals(local)) inTitleInfo=false;
                    }
                }
            } finally { r.close(); }
            if (title.isBlank()) title = filenameTitle(path);
            return new ExtractedBookMetadata(title, authors, series, seq, lang, isbn, publisher, year, annotation,
                    genres, cover, format.format(), format.confidence(), warnings);
        }
    }

    private ExtractedBookMetadata parseEpub(Path source, DetectedBookFormat format, List<String> warnings) throws Exception {
        try (ZipFile zip = SafeZipSupport.openDetected(source, "Organizer metadata archive")) {
            validateArchiveForMetadata(zip);
            String opfPath = packagePath(zip);
            ZipArchiveEntry opf = zip.getEntry(opfPath);
            if (opf == null || opf.getSize() > MAX_XML_ENTRY_BYTES) throw new IllegalArgumentException("EPUB package missing/too large");
            List<String> authors=new ArrayList<>(), genres=new ArrayList<>(); String title="",lang="",isbn="",publisher="",series="",seq="",annotation=""; Integer year=null; boolean cover=false;
            try (InputStream in = boundedEntryStream(zip, opf, MAX_XML_ENTRY_BYTES)) {
                XMLStreamReader r=SecureXmlInputFactory.create(true, false).createXMLStreamReader(in);
                try {
                    while(r.hasNext()) {
                        int e=r.next(); if(e!=XMLStreamConstants.START_ELEMENT) continue;
                        String local=r.getLocalName();
                        if ("item".equals(local) && value(attr(r,"properties")).contains("cover-image")) cover=true;
                        else if (Set.of("title","creator","language","identifier","publisher","date","subject","description").contains(local)) {
                            String text=safeElementText(r, warnings, local);
                            switch(local) { case "title" -> title=firstNonBlank(title,text); case "creator" -> {if(!text.isBlank())authors.add(text);} case "language" -> lang=firstNonBlank(lang,text); case "identifier" -> {if(isbn.isBlank() && text.replaceAll("[- ]","").matches("(?i)(?:ISBN)?(?:97[89])?\\d{9}[\\dX]")) isbn=text;} case "publisher" -> publisher=firstNonBlank(publisher,text); case "date" -> {if(year==null && text.matches("\\d{4}.*")) year=Integer.valueOf(text.substring(0,4));} case "subject" -> {if(!text.isBlank())genres.add(text);} case "description" -> annotation=firstNonBlank(annotation,text); }
                        } else if ("meta".equals(local)) {
                            String name=value(attr(r,"name")), content=value(attr(r,"content")), property=value(attr(r,"property"));
                            if ("calibre:series".equalsIgnoreCase(name)) series=firstNonBlank(series,content);
                            else if ("calibre:series_index".equalsIgnoreCase(name)) seq=firstNonBlank(seq,content);
                            else if (property.endsWith("belongs-to-collection")) { String text=safeElementText(r, warnings, local); series=firstNonBlank(series,text); }
                            else if (property.endsWith("group-position")) { String text=safeElementText(r, warnings, local); seq=firstNonBlank(seq,text); }
                        }
                    }
                } finally { r.close(); }
            }
            if(title.isBlank())title=filenameTitle(source);
            return new ExtractedBookMetadata(title,authors,series,seq,lang,isbn,publisher,year,annotation,genres,cover,format.format(),format.confidence(),warnings);
        }
    }

    private ExtractedBookMetadata parseDocx(Path source, DetectedBookFormat format, List<String> warnings) throws Exception {
        try (ZipFile zip = SafeZipSupport.openDetected(source, "Organizer metadata archive")) {
            validateArchiveForMetadata(zip);
            ZipArchiveEntry core=zip.getEntry("docProps/core.xml");
            if(core==null) return fallback(source,format,warnings);
            String title="",lang="",subject=""; List<String> authors=new ArrayList<>();
            if (core.getSize() > MAX_XML_ENTRY_BYTES) throw new IllegalArgumentException("DOCX core metadata is too large");
            try(InputStream in=boundedEntryStream(zip, core, MAX_XML_ENTRY_BYTES)) { XMLStreamReader r=SecureXmlInputFactory.create(true, false).createXMLStreamReader(in); try { while(r.hasNext()) if(r.next()==XMLStreamConstants.START_ELEMENT) { String l=r.getLocalName(); if(Set.of("title","creator","language","subject").contains(l)) { String t=safeElementText(r, warnings, l); if("title".equals(l))title=firstNonBlank(title,t); else if("creator".equals(l)&&!t.isBlank())authors.add(t); else if("language".equals(l))lang=firstNonBlank(lang,t); else subject=firstNonBlank(subject,t); } } } finally {r.close();} }
            if(title.isBlank()) title=filenameTitle(source);
            return new ExtractedBookMetadata(title,authors,"","",lang,"","",null,subject,List.of(),false,format.format(),format.confidence(),warnings);
        }
    }

    private ExtractedBookMetadata fallback(Path source, DetectedBookFormat format, List<String> warnings) {
        return new ExtractedBookMetadata(filenameTitle(source), List.of(), "", "", "", "", "", null, "", List.of(), false, format.format(), format.confidence(), warnings);
    }

    private String packagePath(ZipFile zip) throws Exception {
        ZipArchiveEntry container=zip.getEntry("META-INF/container.xml"); if(container==null) throw new IllegalArgumentException("container.xml missing");
        if (container.getSize() > MAX_XML_ENTRY_BYTES) throw new IllegalArgumentException("container.xml is too large");
        try(InputStream in=boundedEntryStream(zip, container, MAX_XML_ENTRY_BYTES)) { XMLStreamReader r=SecureXmlInputFactory.create(true, false).createXMLStreamReader(in); try { while(r.hasNext()) if(r.next()==XMLStreamConstants.START_ELEMENT && "rootfile".equals(r.getLocalName())) { String p=attr(r,"full-path"); if(p!=null&&!p.isBlank()&&!unsafe(p)) return p; } } finally {r.close();} }
        throw new IllegalArgumentException("EPUB package path missing");
    }

    private void validateArchiveForMetadata(ZipFile zip) {
        Set<String> names = new HashSet<>();
        long declaredTotal = 0L;
        int inspected = 0;
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        while (entries.hasMoreElements()) {
            if (++inspected > ArchiveSafetyLimits.MAX_ENTRY_COUNT)
                throw new IllegalArgumentException("Archive has too many entries");
            ZipArchiveEntry entry = entries.nextElement();
            String name = entry.getName();
            if (unsafe(name)) throw new IllegalArgumentException("Archive contains an unsafe entry path");
            if (!names.add(name)) throw new IllegalArgumentException("Archive contains a duplicate entry name");
            if (entry.isDirectory()) continue;
            long size = entry.getSize();
            long compressed = entry.getCompressedSize();
            if (ArchiveSafetyLimits.declaredEntryTooLarge(size))
                throw new IllegalArgumentException("Archive contains an oversized entry");
            if (ArchiveSafetyLimits.exceedsCompressionRatio(size, compressed))
                throw new IllegalArgumentException("Archive contains a suspicious compression ratio");
            if (size > 0) {
                try { declaredTotal = Math.addExact(declaredTotal, size); }
                catch (ArithmeticException overflow) { throw new IllegalArgumentException("Archive declared size overflow", overflow); }
                if (declaredTotal > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES)
                    throw new IllegalArgumentException("Archive exceeds cumulative decompressed-size limit");
            }
        }
    }

    private InputStream boundedEntryStream(ZipFile zip, ZipArchiveEntry entry, long maxBytes) throws Exception {
        if (entry == null || entry.isDirectory()) throw new IllegalArgumentException("Archive entry missing");
        if (entry.getSize() > maxBytes) throw new IllegalArgumentException("Archive metadata entry is too large");
        return new BoundedInputStream(zip.getInputStream(entry), maxBytes);
    }
    private String safeElementText(XMLStreamReader r, List<String> warnings, String fieldName) throws Exception {
        StringBuilder text = new StringBuilder(Math.min(1024, MAX_METADATA_FIELD_CHARS));
        int depth = 1;
        boolean truncated = false;
        while (r.hasNext() && depth > 0) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (text.length() < MAX_METADATA_FIELD_CHARS && !text.isEmpty()) text.append(' ');
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (depth > 0 && text.length() < MAX_METADATA_FIELD_CHARS && !text.isEmpty()) text.append(' ');
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (!appendBounded(text, r.getText(), MAX_METADATA_FIELD_CHARS)) truncated = true;
            }
        }
        if (truncated && warnings != null) {
            String field = fieldName == null || fieldName.isBlank() ? "metadata" : fieldName;
            warnings.add("Metadata field was truncated to the safety limit: " + field);
        }
        return normalizeWhitespace(text);
    }
    private boolean appendBounded(StringBuilder target, String value, int maxChars) {
        if (target == null || value == null || value.isEmpty() || maxChars <= 0) return true;
        int remaining = maxChars - target.length();
        if (remaining <= 0) return false;
        if (value.length() <= remaining) { target.append(value); return true; }
        target.append(value, 0, remaining);
        return false;
    }
    private String normalizeWhitespace(CharSequence value) {
        if (value == null || value.length() == 0) return "";
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_ANNOTATION_CHARS));
        boolean pendingSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\u00A0' || Character.isWhitespace(ch)) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) out.append(' ');
                out.append(ch);
                pendingSpace = false;
            }
        }
        return out.toString();
    }
    private String attr(XMLStreamReader r,String n) { for(int i=0;i<r.getAttributeCount();i++) if(n.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i); return null; }
    private String firstNonBlank(String a,String b) { return a==null||a.isBlank()?value(b):a; }
    private String value(String v) { return v==null?"":v.strip(); }
    private String filenameTitle(Path p) { String n=p==null||p.getFileName()==null?"Untitled":p.getFileName().toString(); String l=n.toLowerCase(Locale.ROOT); if(l.endsWith(".fb2.zip"))return n.substring(0,n.length()-8); int d=n.lastIndexOf('.'); return d>0?n.substring(0,d):n; }
    private boolean unsafe(String n) { if(n==null||n.isBlank()||n.startsWith("/")||n.matches("^[A-Za-z]:.*"))return true; for(String p:n.replace('\\','/').split("/"))if("..".equals(p))return true; return false; }
    private boolean exceedsCompressionRatio(long uncompressed, long compressed) {
        if (compressed <= 0 || uncompressed <= 0) return false;
        if (compressed > Long.MAX_VALUE / ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) return false;
        return uncompressed > compressed * ArchiveSafetyLimits.MAX_COMPRESSION_RATIO;
    }

    private static final class BoundedInputStream extends java.io.FilterInputStream {
        private final long maxBytes;
        private long consumed;

        private BoundedInputStream(InputStream in, long maxBytes) { super(in); this.maxBytes = maxBytes; }

        @Override public int read() throws java.io.IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) account(read);
            return read;
        }

        @Override public long skip(long bytes) throws java.io.IOException {
            if (bytes <= 0) return 0L;
            long remaining = Math.max(0L, maxBytes - consumed);
            long probe = remaining == Long.MAX_VALUE ? bytes : Math.min(bytes, remaining + 1L);
            long skipped = super.skip(probe);
            if (skipped > 0) account(skipped);
            return skipped;
        }

        private void account(long bytes) throws java.io.IOException {
            consumed += bytes;
            if (consumed > maxBytes) throw new java.io.IOException("Archive entry exceeds metadata scan limit");
        }
    }

}
