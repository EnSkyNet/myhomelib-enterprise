package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ExtractedContent;
import com.myhomelibcorp.application.port.out.content.ContentExtractor;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.infrastructure.util.LimitedInputStream;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** EPUB content extractor that follows OPF spine order and rejects unsafe archive/XML input. */
@Component
public final class EpubContentExtractor implements ContentExtractor {
    private static final java.util.Set<String> BLOCKS = java.util.Set.of(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "dt", "dd");
    private final XMLInputFactory xmlFactory = SecureXmlInputFactory.create(false, false);

    @Override public String id() { return "epub-content"; }
    @Override public boolean supports(String format) { return "epub".equals(normalizeFormat(format)); }

    @Override
    public ExtractedContent extract(ContentExtractionRequest request, ContentExtractionContext context) throws IOException {
        ContentExtractionContext ctx = context == null ? ContentExtractionContext.none() : context;
        Path temp = Files.createTempFile("myhomelib-content-", ".epub");
        try {
            materialize(request, temp, ctx);
            return extractFromZip(request, temp, ctx);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void materialize(ContentExtractionRequest request, Path temp, ContentExtractionContext context) throws IOException {
        long declared = request.source().size().orElse(-1L);
        if (declared > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
            throw new IOException("EPUB source exceeds safety limit");
        }
        try (InputStream raw = request.source().openStream();
             InputStream bounded = new LimitedInputStream(raw, ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES)) {
            byte[] buffer = new byte[64 * 1024];
            long copied = 0L;
            try (var out = Files.newOutputStream(temp)) {
                int read;
                while ((read = bounded.read(buffer)) >= 0) {
                    context.throwIfCancelled();
                    if (read == 0) continue;
                    out.write(buffer, 0, read);
                    copied += read;
                    if ((copied & ((1L << 20) - 1L)) < buffer.length) {
                        context.report("epub-materialize", copied, Math.max(0L, declared));
                    }
                }
            }
        }
    }

    private ExtractedContent extractFromZip(ContentExtractionRequest request, Path temp, ContentExtractionContext context)
            throws IOException {
        ContentDocumentBuilder document = new ContentDocumentBuilder(request.source().id(), "epub");
        try (ZipFile zip = new ZipFile(temp.toFile())) {
            validateArchive(zip);
            String opfPath = locatePackageDocument(zip);
            EpubPackage pkg = readPackage(zip, opfPath);
            int total = pkg.spineIds().size();
            int chapterNumber = 0;
            for (String idRef : pkg.spineIds()) {
                context.throwIfCancelled();
                ManifestItem item = pkg.manifest().get(idRef);
                if (item == null || !isTextual(item.mediaType())) continue;
                String entryPath = resolveRelative(pkg.baseDirectory(), item.href());
                ZipEntry entry = findEntry(zip, entryPath);
                if (entry == null || entry.isDirectory()) continue;
                checkEntry(entry, "EPUB chapter");
                chapterNumber++;
                ParsedChapter parsed;
                try (InputStream raw = zip.getInputStream(entry);
                     InputStream bounded = new LimitedInputStream(raw, ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
                    parsed = parseXhtml(bounded, context);
                }
                String title = parsed.title().isBlank() ? basenameWithoutExtension(entryPath) : parsed.title();
                document.addChapter("epub-chapter-" + chapterNumber, title, parsed.blocks());
                context.report("epub-spine", Math.min(total, chapterNumber), total);
            }
        } catch (XMLStreamException malformed) {
            throw new IOException("Malformed EPUB XML/XHTML", malformed);
        }
        return document.build();
    }

    private String locatePackageDocument(ZipFile zip) throws IOException, XMLStreamException {
        ZipEntry container = findEntry(zip, "META-INF/container.xml");
        if (container == null) throw new IOException("EPUB container.xml not found");
        checkEntry(container, "EPUB container.xml");
        try (InputStream raw = zip.getInputStream(container);
             InputStream bounded = new LimitedInputStream(raw, ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
            XMLStreamReader reader = xmlFactory.createXMLStreamReader(bounded);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && "rootfile".equalsIgnoreCase(reader.getLocalName())) {
                        String value = attribute(reader, "full-path");
                        if (value != null && !value.isBlank()) return normalizeEntry(value);
                    }
                }
            } finally {
                reader.close();
            }
        }
        throw new IOException("EPUB package document is not declared");
    }

    private EpubPackage readPackage(ZipFile zip, String opfPath) throws IOException, XMLStreamException {
        ZipEntry opf = findEntry(zip, opfPath);
        if (opf == null) throw new IOException("EPUB package document not found: " + opfPath);
        checkEntry(opf, "EPUB package document");
        Map<String, ManifestItem> manifest = new LinkedHashMap<>();
        List<String> spine = new ArrayList<>();
        try (InputStream raw = zip.getInputStream(opf);
             InputStream bounded = new LimitedInputStream(raw, ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
            XMLStreamReader reader = xmlFactory.createXMLStreamReader(bounded);
            try {
                while (reader.hasNext()) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
                    String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                    if ("item".equals(local)) {
                        String id = attribute(reader, "id");
                        String href = attribute(reader, "href");
                        String mediaType = attribute(reader, "media-type");
                        if (id != null && !id.isBlank() && href != null && !href.isBlank()) {
                            manifest.put(id.trim(), new ManifestItem(href.trim(), mediaType == null ? "" : mediaType.trim()));
                        }
                    } else if ("itemref".equals(local)) {
                        String idRef = attribute(reader, "idref");
                        if (idRef != null && !idRef.isBlank()) spine.add(idRef.trim());
                    }
                }
            } finally {
                reader.close();
            }
        }
        if (spine.isEmpty()) throw new IOException("EPUB spine is empty");
        int slash = opfPath.lastIndexOf('/');
        String base = slash < 0 ? "" : opfPath.substring(0, slash + 1);
        return new EpubPackage(base, Map.copyOf(manifest), List.copyOf(spine));
    }

    private ParsedChapter parseXhtml(InputStream input, ContentExtractionContext context) throws XMLStreamException {
        XMLStreamReader reader = xmlFactory.createXMLStreamReader(input);
        List<ContentDocumentBuilder.ParagraphBlock> blocks = new ArrayList<>();
        String title = "";
        String documentTitle = "";
        boolean inBody = false;
        int ignoredDepth = 0;
        int blockDepth = 0;
        String rootBlock = null;
        StringBuilder blockText = null;
        int paragraph = 0;
        boolean inHeadTitle = false;
        StringBuilder headTitle = null;
        try {
            while (reader.hasNext()) {
                context.throwIfCancelled();
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                    if (ignoredDepth > 0) {
                        ignoredDepth++;
                        continue;
                    }
                    if ("script".equals(local) || "style".equals(local)) {
                        ignoredDepth = 1;
                        continue;
                    }
                    if ("body".equals(local)) {
                        inBody = true;
                        continue;
                    }
                    if (!inBody && "title".equals(local)) {
                        inHeadTitle = true;
                        headTitle = new StringBuilder();
                        continue;
                    }
                    if (inBody && BLOCKS.contains(local)) {
                        if (blockDepth == 0) {
                            rootBlock = local;
                            blockText = new StringBuilder();
                        }
                        blockDepth++;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (ignoredDepth > 0) continue;
                    if (inHeadTitle && headTitle != null) headTitle.append(reader.getText());
                    if (inBody && blockDepth > 0 && blockText != null) blockText.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                    if (ignoredDepth > 0) {
                        ignoredDepth--;
                        continue;
                    }
                    if (!inBody && "title".equals(local) && inHeadTitle) {
                        documentTitle = ContentDocumentBuilder.normalize(headTitle == null ? "" : headTitle.toString());
                        inHeadTitle = false;
                        headTitle = null;
                        continue;
                    }
                    if (inBody && blockDepth > 0 && BLOCKS.contains(local)) {
                        blockDepth--;
                        if (blockDepth == 0 && blockText != null) {
                            String text = ContentDocumentBuilder.normalize(blockText.toString());
                            if (!text.isBlank()) {
                                blocks.add(new ContentDocumentBuilder.ParagraphBlock("p" + (++paragraph), text));
                                if (title.isBlank() && rootBlock != null && rootBlock.matches("h[1-6]")) title = text;
                            }
                            blockText = null;
                            rootBlock = null;
                        }
                    }
                    if ("body".equals(local)) inBody = false;
                }
            }
        } finally {
            reader.close();
        }
        if (title.isBlank()) title = documentTitle;
        return new ParsedChapter(title, List.copyOf(blocks));
    }

    private void validateArchive(ZipFile zip) throws IOException {
        int entries = 0;
        long totalDeclared = 0L;
        Enumeration<? extends ZipEntry> iterator = zip.entries();
        while (iterator.hasMoreElements()) {
            ZipEntry entry = iterator.nextElement();
            if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("EPUB contains too many entries");
            if (!ArchiveImportSupport.isSafeEntryName(entry.getName())) {
                throw new IOException("Unsafe EPUB entry name: " + entry.getName());
            }
            checkEntry(entry, "EPUB entry");
            if (!entry.isDirectory() && entry.getSize() > 0L) {
                totalDeclared += entry.getSize();
                if (totalDeclared > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                    throw new IOException("EPUB declared uncompressed size exceeds safety limit");
                }
            }
        }
    }

    private static void checkEntry(ZipEntry entry, String role) throws IOException {
        if (entry == null || entry.isDirectory()) return;
        long size = entry.getSize();
        if (ArchiveSafetyLimits.declaredEntryTooLarge(size)) {
            throw new IOException(role + " exceeds archive safety limit: " + entry.getName());
        }
        long compressed = entry.getCompressedSize();
        if (compressed > 0L && size > 0L
                && size / Math.max(1L, compressed) > ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) {
            throw new IOException(role + " has suspicious compression ratio: " + entry.getName());
        }
    }

    private static ZipEntry findEntry(ZipFile zip, String wanted) {
        if (wanted == null || wanted.isBlank()) return null;
        String normalized = normalizeEntry(wanted);
        ZipEntry direct = zip.getEntry(normalized);
        if (direct != null) return direct;
        Enumeration<? extends ZipEntry> iterator = zip.entries();
        while (iterator.hasMoreElements()) {
            ZipEntry entry = iterator.nextElement();
            if (normalizeEntry(entry.getName()).equalsIgnoreCase(normalized)) return entry;
        }
        return null;
    }

    private static String resolveRelative(String baseDirectory, String href) throws IOException {
        String decoded = decodeHref(href);
        String candidate = normalizeEntry((baseDirectory == null ? "" : baseDirectory) + decoded);
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : candidate.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) throw new IOException("EPUB href escapes archive root: " + href);
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        String resolved = String.join("/", segments);
        if (!ArchiveImportSupport.isSafeEntryName(resolved)) throw new IOException("Unsafe EPUB href: " + href);
        return resolved;
    }

    private static String decodeHref(String href) {
        if (href == null) return "";
        String value = href;
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        try {
            return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    private static boolean isTextual(String mediaType) {
        String value = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        return value.isBlank() || value.contains("xhtml") || value.contains("html") || value.endsWith("+xml");
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (localName.equalsIgnoreCase(reader.getAttributeLocalName(i))) return reader.getAttributeValue(i);
        }
        return null;
    }

    private static String normalizeEntry(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private static String basenameWithoutExtension(String value) {
        String normalized = normalizeEntry(value);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String normalizeFormat(String format) {
        return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }

    private record ManifestItem(String href, String mediaType) { }
    private record EpubPackage(String baseDirectory, Map<String, ManifestItem> manifest, List<String> spineIds) { }
    private record ParsedChapter(String title, List<ContentDocumentBuilder.ParagraphBlock> blocks) { }
}
