package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.shared.archive.ArchiveEntrySafety;
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
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** EPUB content extractor that follows OPF spine order and rejects unsafe archive/XML input. */
@Component
public final class EpubContentExtractor implements ContentExtractor {
    // OPF/container XML is metadata, not chapter content. Bound parser and collection memory separately.
    static final long MAX_METADATA_BYTES = 8L * 1024 * 1024;
    static final int MAX_PACKAGE_RECORDS = ArchiveSafetyLimits.MAX_ENTRY_COUNT;
    private static final java.util.Set<String> BLOCKS = java.util.Set.of(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "dt", "dd");
    // These extractors are singleton beans and may be invoked concurrently by indexing workers.
    // XMLInputFactory implementations are not guaranteed to be thread-safe.
    private final ThreadLocal<XMLInputFactory> xmlFactory =
            ThreadLocal.withInitial(() -> SecureXmlInputFactory.create(false, false));
    private final int maxTextChars;

    public EpubContentExtractor() { this(ContentDocumentBuilder.MAX_TEXT_CHARS); }
    EpubContentExtractor(int maxTextChars) { this.maxTextChars = maxTextChars; }

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
        ContentDocumentBuilder document = new ContentDocumentBuilder(request.source().id(), "epub",
                maxTextChars, ContentDocumentBuilder.MAX_PARAGRAPHS);
        try (ZipFile zip = new ZipFile(temp.toFile())) {
            EntryIndex entries = validateAndIndexArchive(zip, context);
            String opfPath = locatePackageDocument(zip, entries, context);
            EpubPackage pkg = readPackage(zip, entries, opfPath, context);
            int total = pkg.spineIds().size();
            int chapterNumber = 0;
            for (String idRef : pkg.spineIds()) {
                context.throwIfCancelled();
                ManifestItem item = pkg.manifest().get(idRef);
                if (item == null || !isTextual(item.mediaType())) continue;
                String entryPath = resolveRelative(pkg.baseDirectory(), item.href());
                ZipEntry entry = entries.find(entryPath);
                if (entry == null || entry.isDirectory()) continue;
                checkEntry(entry, "EPUB chapter");
                chapterNumber++;
                String title;
                try (InputStream raw = zip.getInputStream(entry);
                     InputStream bounded = new LimitedInputStream(raw, ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
                    title = parseXhtml(bounded, context, document, "epub-chapter-" + chapterNumber);
                }
                document.finishChapter(title.isBlank() ? basenameWithoutExtension(entryPath) : title);
                context.report("epub-spine", Math.min(total, chapterNumber), total);
            }
        } catch (XMLStreamException malformed) {
            throw new IOException("Malformed EPUB XML/XHTML", malformed);
        }
        return document.build();
    }

    private String locatePackageDocument(ZipFile zip, EntryIndex entries, ContentExtractionContext context)
            throws IOException, XMLStreamException {
        context.throwIfCancelled();
        ZipEntry container = entries.find("META-INF/container.xml");
        if (container == null) throw new IOException("EPUB container.xml not found");
        checkMetadataEntry(container, "EPUB container.xml");
        try (InputStream raw = zip.getInputStream(container);
             InputStream bounded = new LimitedInputStream(raw, MAX_METADATA_BYTES)) {
            XMLStreamReader reader = xmlFactory.get().createXMLStreamReader(bounded);
            try {
                while (reader.hasNext()) {
                    context.throwIfCancelled();
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

    private EpubPackage readPackage(ZipFile zip, EntryIndex entries, String opfPath, ContentExtractionContext context)
            throws IOException, XMLStreamException {
        context.throwIfCancelled();
        ZipEntry opf = entries.find(opfPath);
        if (opf == null) throw new IOException("EPUB package document not found: " + opfPath);
        checkMetadataEntry(opf, "EPUB package document");
        Map<String, ManifestItem> manifest = new LinkedHashMap<>();
        List<String> spine = new ArrayList<>();
        int manifestRecords = 0;
        int spineRecords = 0;
        try (InputStream raw = zip.getInputStream(opf);
             InputStream bounded = new LimitedInputStream(raw, MAX_METADATA_BYTES)) {
            XMLStreamReader reader = xmlFactory.get().createXMLStreamReader(bounded);
            try {
                while (reader.hasNext()) {
                    context.throwIfCancelled();
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
                    String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                    if ("item".equals(local)) {
                        if (++manifestRecords > MAX_PACKAGE_RECORDS) {
                            throw new IOException("EPUB manifest record count exceeds safety limit of " + MAX_PACKAGE_RECORDS);
                        }
                        String id = attribute(reader, "id");
                        String href = attribute(reader, "href");
                        String mediaType = attribute(reader, "media-type");
                        if (id != null && !id.isBlank() && href != null && !href.isBlank()) {
                            manifest.put(id.trim(), new ManifestItem(href.trim(), mediaType == null ? "" : mediaType.trim()));
                        }
                    } else if ("itemref".equals(local)) {
                        if (++spineRecords > MAX_PACKAGE_RECORDS) {
                            throw new IOException("EPUB spine record count exceeds safety limit of " + MAX_PACKAGE_RECORDS);
                        }
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
        // The private package owns these collections; wrappers avoid copying both large tables at once.
        return new EpubPackage(base, Collections.unmodifiableMap(manifest), Collections.unmodifiableList(spine));
    }

    private String parseXhtml(InputStream input, ContentExtractionContext context,
                              ContentDocumentBuilder document, String chapterId) throws XMLStreamException, IOException {
        XMLStreamReader reader = xmlFactory.get().createXMLStreamReader(input);
        String title = "";
        String documentTitle = "";
        boolean inBody = false;
        int ignoredDepth = 0;
        int blockDepth = 0;
        String rootBlock = null;
        NormalizedTextBuffer blockText = null;
        int paragraph = 0;
        boolean inHeadTitle = false;
        NormalizedTextBuffer headTitle = null;
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
                        headTitle = document.titleBuffer();
                        continue;
                    }
                    if (inBody && BLOCKS.contains(local)) {
                        if (blockDepth == 0) {
                            rootBlock = local;
                            blockText = document.paragraphBuffer();
                        } else {
                            blockText.separate();
                        }
                        blockDepth++;
                    } else if (inBody && "br".equals(local) && blockText != null) {
                        blockText.separate();
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (ignoredDepth > 0) continue;
                    if (inHeadTitle && headTitle != null) {
                        headTitle.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
                    }
                    if (inBody && blockDepth > 0 && blockText != null) {
                        blockText.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                    if (ignoredDepth > 0) {
                        ignoredDepth--;
                        continue;
                    }
                    if (!inBody && "title".equals(local) && inHeadTitle) {
                        documentTitle = headTitle == null ? "" : headTitle.text();
                        inHeadTitle = false;
                        headTitle = null;
                        continue;
                    }
                    if (inBody && blockDepth > 0 && BLOCKS.contains(local)) {
                        blockDepth--;
                        if (blockDepth == 0 && blockText != null) {
                            String text = blockText.text();
                            if (!text.isBlank()) {
                                document.addParagraph(chapterId, "p" + (++paragraph), text);
                                if (title.isBlank() && rootBlock != null && rootBlock.matches("h[1-6]")) title = text;
                            }
                            blockText = null;
                            rootBlock = null;
                        } else if (blockText != null) {
                            blockText.separate();
                        }
                    }
                    if ("body".equals(local)) inBody = false;
                }
            }
        } finally {
            reader.close();
        }
        if (title.isBlank()) title = documentTitle;
        return title;
    }

    /** Validate and index the ZIP once. Repeated case-insensitive fallback scans cost O(chapters * entries). */
    private EntryIndex validateAndIndexArchive(ZipFile zip, ContentExtractionContext context) throws IOException {
        context.throwIfCancelled();
        int count = 0;
        long totalDeclared = 0L;
        Map<String, ZipEntry> exact = new HashMap<>();
        Map<String, ZipEntry> insensitive = new HashMap<>();
        Enumeration<? extends ZipEntry> iterator = zip.entries();
        while (iterator.hasMoreElements()) {
            context.throwIfCancelled();
            ZipEntry entry = iterator.nextElement();
            if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("EPUB contains too many entries");
            if (!ArchiveImportSupport.isSafeEntryName(entry.getName())) {
                throw new IOException("Unsafe EPUB entry name: " + entry.getName());
            }
            checkEntry(entry, "EPUB entry");
            exact.putIfAbsent(entry.getName(), entry);
            insensitive.putIfAbsent(normalizeEntry(entry.getName()).toLowerCase(Locale.ROOT), entry);
            if (!entry.isDirectory() && entry.getSize() > 0L) {
                totalDeclared += entry.getSize();
                if (totalDeclared > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                    throw new IOException("EPUB declared uncompressed size exceeds safety limit");
                }
            }
        }
        return new EntryIndex(exact, insensitive);
    }

    private static void checkEntry(ZipEntry entry, String role) throws IOException {
        if (entry == null) return;
        ArchiveEntrySafety.check(entry.getName(), entry.isDirectory(), entry.getSize(), entry.getCompressedSize(), role);
    }

    private static void checkMetadataEntry(ZipEntry entry, String role) throws IOException {
        checkEntry(entry, role);
        if (entry.getSize() > MAX_METADATA_BYTES) {
            throw new IOException(role + " metadata exceeds safety limit of " + MAX_METADATA_BYTES + " bytes");
        }
    }

    /** Preserve exact-name precedence and the first case-insensitive match, without rescanning ZIP. */
    private record EntryIndex(Map<String, ZipEntry> exact, Map<String, ZipEntry> insensitive) {
        private ZipEntry find(String wanted) {
            if (wanted == null || wanted.isBlank()) return null;
            String normalized = normalizeEntry(wanted);
            ZipEntry direct = exact.get(normalized);
            return direct != null ? direct : insensitive.get(normalized.toLowerCase(Locale.ROOT));
        }
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
}
