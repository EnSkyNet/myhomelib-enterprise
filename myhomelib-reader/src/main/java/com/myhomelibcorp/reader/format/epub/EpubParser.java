package com.myhomelibcorp.reader.format.epub;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.resource.HybridResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;

import javax.xml.stream.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EPUB2/EPUB3 reader parser. Text follows the OPF spine; TOC follows EPUB3 nav
 * or EPUB2 NCX, and embedded images are loaded through bounded streaming.
 */
public final class EpubParser implements BookParser {
    private static final int MAX_METADATA_TEXT_CHARS = 64 * 1024;
    private final XMLInputFactory xmlFactory = secureXmlFactory();

    @Override
    public BookDocumentMetadata readMetadata(BookSource source) throws IOException {
        if (source == null) throw new IOException("EPUB source is null");
        try (MaterializedSource materialized = materialize(source);
             ZipFile zip = new ZipFile(materialized.path().toFile())) {
            validateArchive(zip);
            PackageData pkg = readPackage(zip, source);
            boolean images = pkg.manifest().values().stream().anyMatch(i -> isImage(i.mediaType()));
            return new BookDocumentMetadataSnapshot(pkg.metadata(), source.size().orElse(0), images,
                    Math.max(1, pkg.spinePaths().size()));
        } catch (XMLStreamException e) {
            throw new IOException("Invalid EPUB metadata: " + e.getMessage(), e);
        }
    }

    @Override
    public ReaderDocument parse(BookSource source, ParseOptions options) throws IOException {
        if (source == null) throw new IOException("EPUB source is null");
        ParseOptions effective = options != null ? options : ParseOptions.defaultOptions();
        HybridResourceRepository resources = new HybridResourceRepository();
        boolean success = false;
        try (MaterializedSource materialized = materialize(source);
             ZipFile zip = new ZipFile(materialized.path().toFile())) {
            validateArchive(zip);
            PackageData pkg = readPackage(zip, source);
            preloadImages(zip, pkg, resources, effective);

            TextStorageImpl text = new TextStorageImpl();
            List<ChapterIndex> chapters = new ArrayList<>();
            Map<String, Long> documentOffsets = new LinkedHashMap<>();
            List<String> documents = pkg.spinePaths().isEmpty() ? fallbackDocuments(zip) : pkg.spinePaths();
            if (documents.isEmpty()) throw new IOException("EPUB contains no readable spine documents");

            int chapterNumber = 0;
            for (String path : documents) {
                checkCancelled();
                ZipEntry entry = findZip(zip, path);
                if (entry == null || entry.isDirectory()) continue;
                checkEntry(entry, "EPUB document");
                long start = text.length();
                documentOffsets.put(norm(path), start);
                int paragraphsBefore = text.getParagraphCount();
                String detectedTitle;
                try (InputStream in = bounded(zip.getInputStream(entry), ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
                    detectedTitle = parseXhtml(in, path, text, resources, effective, documentOffsets);
                } catch (XMLStreamException e) {
                    throw new IOException("Invalid EPUB XHTML '" + path + "': " + e.getMessage(), e);
                }
                long end = text.length();
                if (end <= start) continue;
                String title = firstNonBlank(detectedTitle, fileTitle(path), "Розділ " + (++chapterNumber));
                chapters.add(new ChapterIndex("epub-" + chapters.size(), title, start, end,
                        text.getParagraphCount() - paragraphsBefore));
            }
            if (text.length() == 0) throw new IOException("EPUB contains no readable text");

            DefaultTableOfContents toc = buildToc(zip, pkg, documentOffsets, chapters, effective.buildToc());
            success = true;
            return CompactReaderDocument.builder()
                    .metadata(pkg.metadata())
                    .chapters(List.copyOf(chapters))
                    .resources(resources)
                    .text(text)
                    .toc(toc)
                    .totalTextLength(text.length())
                    .build();
        } catch (XMLStreamException e) {
            throw new IOException("Invalid EPUB package: " + e.getMessage(), e);
        } finally {
            if (!success) resources.close();
        }
    }

    private PackageData readPackage(ZipFile zip, BookSource source) throws IOException, XMLStreamException {
        String opfPath = locateOpf(zip);
        if (opfPath == null) throw new IOException("EPUB META-INF/container.xml has no rootfile");
        ZipEntry opf = findZip(zip, opfPath);
        if (opf == null) throw new IOException("EPUB OPF not found: " + opfPath);
        checkEntry(opf, "EPUB OPF");

        Map<String, ManifestItem> manifest = new LinkedHashMap<>();
        List<String> spineIds = new ArrayList<>();
        List<String> authors = new ArrayList<>();
        List<String> genres = new ArrayList<>();
        String title = null, language = "", publisher = "", year = "", isbn = null;
        String series = null;
        Integer sequence = null;
        String tocId = null;
        String base = parentZipPath(opfPath);

        try (InputStream in = bounded(zip.getInputStream(opf), ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
            XMLStreamReader r = xmlFactory.createXMLStreamReader(in);
            boolean inMetadata = false;
            try {
                int events = 0;
                while (r.hasNext()) {
                    if (((++events) & 0xFF) == 0) checkCancelled();
                    int event = r.next();
                    if (event == XMLStreamConstants.END_ELEMENT && "metadata".equalsIgnoreCase(r.getLocalName())) {
                        inMetadata = false;
                        continue;
                    }
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    String local = lower(r.getLocalName());
                    if ("metadata".equals(local)) {
                        inMetadata = true;
                        continue;
                    }

                    if (!inMetadata) {
                        switch (local) {
                            case "item" -> {
                                String id = attr(r, "id"), href = attr(r, "href");
                                if (id != null && href != null)
                                    manifest.put(id, new ManifestItem(id, href, attr(r, "media-type"), attr(r, "properties")));
                            }
                            case "spine" -> tocId = attr(r, "toc");
                            case "itemref" -> {
                                String idref = attr(r, "idref");
                                if (idref != null && !"no".equalsIgnoreCase(attr(r, "linear"))) spineIds.add(idref);
                            }
                            default -> { }
                        }
                        continue;
                    }

                    switch (local) {
                        case "title" -> title = firstNonBlank(readMetadataText(r), title);
                        case "creator" -> { String v = readMetadataText(r); if (!v.isBlank()) authors.add(v); }
                        case "language" -> language = firstNonBlank(readMetadataText(r), language);
                        case "publisher" -> publisher = firstNonBlank(readMetadataText(r), publisher);
                        case "subject" -> { String v = readMetadataText(r); if (!v.isBlank()) genres.add(v); }
                        case "date" -> {
                            String v = readMetadataText(r);
                            if (v.length() >= 4 && v.substring(0, 4).chars().allMatch(Character::isDigit)) year = v.substring(0, 4);
                        }
                        case "identifier" -> {
                            String scheme = firstNonBlank(attrByLocal(r, "scheme"), "");
                            String v = readMetadataText(r);
                            if (isbn == null && (scheme.toLowerCase(Locale.ROOT).contains("isbn") || v.replaceAll("[^0-9Xx]", "").length() >= 10)) isbn = v;
                        }
                        case "meta" -> {
                            String name = lower(firstNonBlank(attr(r, "name"), ""));
                            String property = lower(firstNonBlank(attr(r, "property"), ""));
                            String content = firstNonBlank(attr(r, "content"), "");
                            if ("calibre:series".equals(name)) series = firstNonBlank(content, series);
                            if ("calibre:series_index".equals(name)) sequence = parseSequence(content, sequence);
                            if (property.endsWith("belongs-to-collection")) {
                                String v = readMetadataText(r); if (!v.isBlank()) series = v;
                            } else if (property.endsWith("group-position")) {
                                String v = readMetadataText(r); sequence = parseSequence(v, sequence);
                            }
                        }
                        default -> { }
                    }
                }
            } finally { r.close(); }
        }

        List<String> spine = new ArrayList<>();
        for (String id : spineIds) {
            ManifestItem item = manifest.get(id);
            if (item != null) spine.add(resolveZipPath(base, item.href()));
        }
        String navPath = null;
        for (ManifestItem item : manifest.values()) {
            if (hasProperty(item.properties(), "nav")) { navPath = resolveZipPath(base, item.href()); break; }
        }
        String ncxPath = null;
        if (tocId != null && manifest.containsKey(tocId)) ncxPath = resolveZipPath(base, manifest.get(tocId).href());
        if (ncxPath == null) {
            for (ManifestItem item : manifest.values()) {
                if ("application/x-dtbncx+xml".equalsIgnoreCase(item.mediaType())) {
                    ncxPath = resolveZipPath(base, item.href()); break;
                }
            }
        }

        String effectiveTitle = firstNonBlank(title, fileTitle(source.name()), "Без назви");
        if (authors.isEmpty()) authors.add("Невідомий автор");
        BookMetadata metadata = new BookMetadata(source.id(), effectiveTitle, List.copyOf(authors), language,
                series, sequence, List.copyOf(genres), "", publisher, year, isbn, source.size().orElse(0));
        return new PackageData(metadata, Map.copyOf(manifest), List.copyOf(spine), navPath, ncxPath, base);
    }

    private String locateOpf(ZipFile zip) throws IOException, XMLStreamException {
        ZipEntry container = findZip(zip, "META-INF/container.xml");
        if (container == null) throw new IOException("EPUB META-INF/container.xml not found");
        checkEntry(container, "EPUB container.xml");
        try (InputStream in = bounded(zip.getInputStream(container), ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
            XMLStreamReader r = xmlFactory.createXMLStreamReader(in);
            String packagePath = null;
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && "rootfile".equalsIgnoreCase(r.getLocalName())) {
                        String fullPath = attr(r, "full-path");
                        if (packagePath == null && fullPath != null && !fullPath.isBlank()) packagePath = norm(fullPath);
                    }
                }
            } finally { r.close(); }
            return packagePath;
        }
    }

    private void preloadImages(ZipFile zip, PackageData pkg, HybridResourceRepository resources, ParseOptions options) throws IOException {
        for (ManifestItem item : pkg.manifest().values()) {
            checkCancelled();
            if (!isImage(item.mediaType())) continue;
            String id = resolveZipPath(pkg.opfBase(), item.href());
            if (!options.loadImages()) { resources.addMetadata(id, item.mediaType()); continue; }
            ZipEntry entry = findZip(zip, id);
            if (entry == null || entry.isDirectory()) continue;
            checkEntry(entry, "EPUB image");
            long max = options.maxImageSizeBytes() > 0
                    ? Math.min((long) options.maxImageSizeBytes(), ArchiveSafetyLimits.MAX_ENTRY_BYTES)
                    : ArchiveSafetyLimits.MAX_ENTRY_BYTES;
            if (entry.getSize() >= 0 && entry.getSize() > max) continue;
            try (InputStream in = bounded(zip.getInputStream(entry), max)) {
                resources.add(id, item.mediaType(), in, max);
            }
        }
    }

    private String parseXhtml(InputStream input, String documentPath, TextStorageImpl text,
                              HybridResourceRepository resources, ParseOptions options,
                              Map<String, Long> navigationOffsets)
            throws XMLStreamException, IOException {
        XMLStreamReader r = xmlFactory.createXMLStreamReader(input);
        Deque<InlineSpan> inline = new ArrayDeque<>();
        StringBuilder heading = new StringBuilder();
        String firstHeading = null;
        int headingDepth = 0;
        boolean lastWhitespace = text.length() > 0 && Character.isWhitespace(text.getText(text.length() - 1, text.length()).charAt(0));
        try {
            int events = 0;
            while (r.hasNext()) {
                if (((++events) & 0xFF) == 0) checkCancelled();
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String tag = lower(r.getLocalName());
                    TextStyle blockStyle = blockStyle(tag);
                    if (blockStyle != null) {
                        appendBoundary(text);
                        text.startParagraph(blockStyle);
                        if (blockStyle.isHeading()) { heading.setLength(0); headingDepth++; }
                        lastWhitespace = true;
                    }
                    String anchor = firstNonBlank(attr(r, "id"), attrByLocal(r, "id"), "a".equals(tag) ? attr(r, "name") : null);
                    if (anchor != null && !anchor.isBlank()) {
                        navigationOffsets.put(norm(documentPath) + "#" + decodeFragment(anchor), (long) text.length());
                    }
                    TextStyle inlineStyle = inlineStyle(tag);
                    if (inlineStyle != null) inline.push(new InlineSpan(tag, text.length(), inlineStyle));
                    if ("br".equals(tag)) { text.append("\n", TextStyle.NORMAL); lastWhitespace = true; }
                    if ("img".equals(tag) && options.loadImages()) {
                        String src = attr(r, "src");
                        if (src != null) {
                            String id = resolveZipPath(parentZipPath(documentPath), src);
                            if (resources.exists(id)) {
                                appendBoundary(text);
                                text.startParagraph(TextStyle.NORMAL);
                                text.append("[IMAGE:" + id + "]", TextStyle.NORMAL);
                                text.append("\n", TextStyle.NORMAL);
                                lastWhitespace = true;
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    String raw = r.getText();
                    StringBuilder normalized = new StringBuilder(raw.length());
                    for (int i = 0; i < raw.length(); i++) {
                        char c = raw.charAt(i);
                        if (Character.isWhitespace(c)) {
                            if (!lastWhitespace && normalized.length() > 0) { normalized.append(' '); lastWhitespace = true; }
                            else if (!lastWhitespace && normalized.length() == 0) { normalized.append(' '); lastWhitespace = true; }
                        } else {
                            normalized.append(c); lastWhitespace = false;
                        }
                    }
                    if (!normalized.isEmpty()) {
                        String chunk = normalized.toString();
                        text.append(chunk, TextStyle.NORMAL);
                        if (headingDepth > 0) heading.append(chunk);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String tag = lower(r.getLocalName());
                    if (!inline.isEmpty() && inline.peek().tag().equals(tag)) {
                        InlineSpan s = inline.pop();
                        text.addSpan(s.start(), text.length(), s.style());
                    }
                    TextStyle block = blockStyle(tag);
                    if (block != null) {
                        if (block.isHeading() && headingDepth > 0) {
                            headingDepth--;
                            if (firstHeading == null) {
                                String h = heading.toString().strip();
                                if (!h.isBlank()) firstHeading = h;
                            }
                        }
                        appendBoundary(text);
                        lastWhitespace = true;
                    }
                }
            }
        } finally { r.close(); }
        return firstHeading;
    }

    private DefaultTableOfContents buildToc(ZipFile zip, PackageData pkg, Map<String, Long> offsets,
                                            List<ChapterIndex> chapters, boolean enabled) throws IOException, XMLStreamException {
        DefaultTableOfContents toc = new DefaultTableOfContents();
        if (!enabled) return toc;
        List<NavItem> items = List.of();
        if (pkg.navPath() != null) {
            ZipEntry nav = findZip(zip, pkg.navPath());
            if (nav != null) {
                checkEntry(nav, "EPUB nav");
                try (InputStream in = bounded(zip.getInputStream(nav), ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
                    items = parseNav(in, pkg.navPath());
                }
            }
        }
        if (items.isEmpty() && pkg.ncxPath() != null) {
            ZipEntry ncx = findZip(zip, pkg.ncxPath());
            if (ncx != null) {
                checkEntry(ncx, "EPUB NCX");
                try (InputStream in = bounded(zip.getInputStream(ncx), ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
                    items = parseNcx(in, pkg.ncxPath());
                }
            }
        }
        for (NavItem item : items) {
            String target = norm(item.target());
            Long offset = offsets.get(target);
            if (offset == null) offset = offsets.get(norm(stripFragment(target)));
            if (offset != null && item.title() != null && !item.title().isBlank()) toc.addEntry(item.title(), offset, Math.max(1, item.level()));
        }
        if (toc.isEmpty()) for (ChapterIndex chapter : chapters) toc.addEntry(chapter.title(), chapter.startOffset(), 1);
        return toc;
    }

    private List<NavItem> parseNav(InputStream input, String navPath) throws XMLStreamException {
        XMLStreamReader r = xmlFactory.createXMLStreamReader(input);
        List<NavItem> out = new ArrayList<>();
        boolean inToc = false, inAnchor = false;
        int navDepth = 0, listDepth = 0;
        String href = null;
        StringBuilder label = new StringBuilder();
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String tag = lower(r.getLocalName());
                    if ("nav".equals(tag)) {
                        String type = firstNonBlank(attr(r, "type"), attrByLocal(r, "type"));
                        if (!inToc && hasToken(type, "toc")) { inToc = true; navDepth = 1; listDepth = 0; }
                        else if (inToc) navDepth++;
                    } else if (inToc && "ol".equals(tag)) listDepth++;
                    else if (inToc && "a".equals(tag)) {
                        inAnchor = true; label.setLength(0); href = attr(r, "href");
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && inAnchor) {
                    label.append(r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String tag = lower(r.getLocalName());
                    if (inToc && "a".equals(tag) && inAnchor) {
                        String title = normalize(label.toString());
                        if (!title.isBlank() && href != null) out.add(new NavItem(Math.max(1, listDepth), title,
                                resolveNavigationTarget(parentZipPath(navPath), href)));
                        inAnchor = false; href = null;
                    } else if (inToc && "ol".equals(tag)) listDepth = Math.max(0, listDepth - 1);
                    else if (inToc && "nav".equals(tag)) { if (--navDepth <= 0) inToc = false; }
                }
            }
        } finally { r.close(); }
        return out;
    }

    private List<NavItem> parseNcx(InputStream input, String ncxPath) throws XMLStreamException {
        XMLStreamReader r = xmlFactory.createXMLStreamReader(input);
        List<NavItem> out = new ArrayList<>();
        int depth = 0;
        Deque<NcxState> points = new ArrayDeque<>();
        boolean inText = false;
        StringBuilder label = new StringBuilder();
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String tag = lower(r.getLocalName());
                    if ("navpoint".equals(tag)) { depth++; points.push(new NcxState(depth)); }
                    else if (!points.isEmpty() && "text".equals(tag)) { inText = true; label.setLength(0); }
                    else if (!points.isEmpty() && "content".equals(tag)) points.peek().target = attr(r, "src");
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && inText) label.append(r.getText());
                else if (event == XMLStreamConstants.END_ELEMENT) {
                    String tag = lower(r.getLocalName());
                    if ("text".equals(tag) && inText && !points.isEmpty()) { points.peek().title = normalize(label.toString()); inText = false; }
                    else if ("navpoint".equals(tag) && !points.isEmpty()) {
                        NcxState state = points.pop();
                        if (state.target != null && state.title != null && !state.title.isBlank())
                            out.add(new NavItem(state.level, state.title, resolveNavigationTarget(parentZipPath(ncxPath), state.target)));
                        depth = Math.max(0, depth - 1);
                    }
                }
            }
        } finally { r.close(); }
        return out;
    }

    private void validateArchive(ZipFile zip) throws IOException {
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("EPUB contains too many ZIP entries");
            checkEntry(e, "EPUB entry");
        }
    }

    private void checkEntry(ZipEntry entry, String role) throws IOException {
        if (entry == null || entry.isDirectory()) return;
        if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) throw new IOException(role + " exceeds Reader archive limit: " + entry.getName());
        long compressed = entry.getCompressedSize(), size = entry.getSize();
        if (compressed > 0 && size > 0 && size / Math.max(1, compressed) > ArchiveSafetyLimits.MAX_COMPRESSION_RATIO)
            throw new IOException(role + " has suspicious compression ratio: " + entry.getName());
    }

    private List<String> fallbackDocuments(ZipFile zip) {
        return Collections.list(zip.entries()).stream().filter(e -> !e.isDirectory())
                .map(ZipEntry::getName).filter(n -> {
                    String x = n.toLowerCase(Locale.ROOT); return x.endsWith(".xhtml") || x.endsWith(".html") || x.endsWith(".htm");
                }).sorted().toList();
    }

    private MaterializedSource materialize(BookSource source) throws IOException {
        if (source instanceof FileBookSource file && Files.isRegularFile(file.getPath())) return new MaterializedSource(file.getPath(), false);
        Path tmp = Files.createTempFile("myhomelib-reader-", ".epub");
        try (InputStream in = source.openStream(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
            while ((n = in.read(buffer)) >= 0) {
                checkCancelled();
                if (n == 0) continue;
                total += n;
                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IOException("EPUB source exceeds Reader safety limit");
                out.write(buffer, 0, n);
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp); throw e;
        }
        return new MaterializedSource(tmp, true);
    }

    private InputStream bounded(InputStream input, long max) {
        return new FilterInputStream(input) {
            private long total;
            private void add(int n) throws IOException { if (n > 0 && (total += n) > max) throw new IOException("EPUB entry exceeds Reader safety limit"); }
            @Override public int read() throws IOException { int x = super.read(); if (x >= 0) add(1); return x; }
            @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); add(n); return n; }
        };
    }

    private ZipEntry findZip(ZipFile zip, String wanted) {
        if (wanted == null) return null;
        String normalized = norm(stripFragment(wanted));
        ZipEntry direct = zip.getEntry(normalized);
        if (direct != null) return direct;
        Enumeration<? extends ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) { ZipEntry e = it.nextElement(); if (norm(e.getName()).equalsIgnoreCase(normalized)) return e; }
        return null;
    }

    private static XMLInputFactory secureXmlFactory() {
        return SecureXmlInputFactory.create(true, false);
    }
    private String readMetadataText(XMLStreamReader r) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if ((event == XMLStreamConstants.CHARACTERS
                    || event == XMLStreamConstants.CDATA
                    || event == XMLStreamConstants.SPACE
                    || event == XMLStreamConstants.ENTITY_REFERENCE)
                    && text.length() < MAX_METADATA_TEXT_CHARS) {
                String chunk = r.getText();
                if (chunk != null && !chunk.isEmpty()) {
                    int remaining = MAX_METADATA_TEXT_CHARS - text.length();
                    text.append(chunk, 0, Math.min(chunk.length(), remaining));
                }
            }
        }
        return normalize(text.toString());
    }
    private String attr(XMLStreamReader r, String local) { String v = r.getAttributeValue(null, local); return v == null || v.isBlank() ? null : v.trim(); }
    private String attrByLocal(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++) if (local.equalsIgnoreCase(r.getAttributeLocalName(i))) {
            String v = r.getAttributeValue(i); return v == null || v.isBlank() ? null : v.trim();
        }
        return null;
    }
    private static boolean isImage(String media) { return media != null && media.toLowerCase(Locale.ROOT).startsWith("image/"); }
    private static boolean hasProperty(String properties, String property) { return hasToken(properties, property); }
    private static boolean hasToken(String value, String token) { return value != null && Arrays.stream(value.trim().split("\\s+")).anyMatch(token::equalsIgnoreCase); }
    private static String lower(String v) { return v == null ? "" : v.toLowerCase(Locale.ROOT); }
    private static String normalize(String v) { return v == null ? "" : v.replace('\r', ' ').replaceAll("\\s+", " ").trim(); }
    private static String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v.trim(); return null; }
    private static Integer parseSequence(String value, Integer fallback) { try { return value == null || value.isBlank() ? fallback : (int) Math.round(Double.parseDouble(value)); } catch (NumberFormatException e) { return fallback; } }
    private static String fileTitle(String path) { if (path == null || path.isBlank()) return null; String n = Path.of(norm(stripFragment(path))).getFileName().toString(); int dot = n.lastIndexOf('.'); return dot > 0 ? n.substring(0, dot) : n; }
    private static String parentZipPath(String path) { String n = norm(path); int slash = n.lastIndexOf('/'); return slash < 0 ? "" : n.substring(0, slash); }
    private static String resolveNavigationTarget(String base, String href) {
        String raw = href == null ? "" : href;
        int hash = raw.indexOf('#');
        String fragment = hash >= 0 && hash + 1 < raw.length() ? decodeFragment(raw.substring(hash + 1)) : "";
        String path = resolveZipPath(base, stripFragment(raw));
        return fragment.isBlank() ? path : path + "#" + fragment;
    }

    private static String decodeFragment(String value) {
        if (value == null || value.isBlank()) return "";
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        catch (Exception ignored) { return value; }
    }

    private static String stripFragment(String path) { if (path == null) return ""; int hash = path.indexOf('#'); return hash < 0 ? path : path.substring(0, hash); }
    private static String norm(String path) { return path == null ? "" : path.replace('\\', '/').replaceAll("^/+", ""); }
    private static String resolveZipPath(String base, String href) {
        String raw = stripFragment(href == null ? "" : href); int query = raw.indexOf('?'); if (query >= 0) raw = raw.substring(0, query);
        try { raw = URLDecoder.decode(raw, StandardCharsets.UTF_8); } catch (IllegalArgumentException ignored) { }
        String combined = base == null || base.isBlank() ? raw : base + "/" + raw;
        Deque<String> parts = new ArrayDeque<>();
        for (String part : combined.replace('\\', '/').split("/")) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) { if (!parts.isEmpty()) parts.removeLast(); continue; }
            parts.addLast(part);
        }
        return String.join("/", parts);
    }
    private static void appendBoundary(TextStorageImpl text) { if (text.length() > 0 && !"\n".equals(text.getText(text.length() - 1, text.length()))) text.append("\n", TextStyle.NORMAL); }
    private static TextStyle blockStyle(String tag) {
        return switch (tag) {
            case "h1" -> TextStyle.CHAPTER_TITLE;
            case "h2", "h3", "h4", "h5", "h6" -> TextStyle.SECTION_TITLE;
            case "blockquote" -> TextStyle.QUOTE; case "pre" -> TextStyle.CODE;
            case "p", "div", "li", "dt", "dd", "section", "article" -> TextStyle.NORMAL;
            default -> null;
        };
    }
    private static TextStyle inlineStyle(String tag) {
        return switch (tag) {
            case "b", "strong" -> TextStyle.BOLD; case "i", "em" -> TextStyle.ITALIC; case "u" -> TextStyle.UNDERLINE;
            case "code", "kbd", "samp" -> TextStyle.CODE; case "a" -> TextStyle.LINK; case "sub" -> TextStyle.SUBSCRIPT; case "sup" -> TextStyle.SUPERSCRIPT;
            default -> null;
        };
    }

    private record ManifestItem(String id, String href, String mediaType, String properties) { }
    private record PackageData(BookMetadata metadata, Map<String, ManifestItem> manifest, List<String> spinePaths,
                               String navPath, String ncxPath, String opfBase) { }
    private record NavItem(int level, String title, String target) { }
    private record InlineSpan(String tag, int start, TextStyle style) { }
    private static final class NcxState { final int level; String title; String target; NcxState(int level) { this.level = level; } }
    private record MaterializedSource(Path path, boolean delete) implements AutoCloseable {
        @Override public void close() throws IOException { if (delete) Files.deleteIfExists(path); }
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("EPUB parsing cancelled");
    }

}
