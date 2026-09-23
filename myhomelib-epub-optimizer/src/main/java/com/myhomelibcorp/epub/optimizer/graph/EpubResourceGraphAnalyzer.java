package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public class EpubResourceGraphAnalyzer {
    private static final long MAX_CSS_SCAN_BYTES = 16L * 1024L * 1024L;
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*(['\"]?)([^)'\"]+)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT = Pattern.compile("@import\\s+(?:url\\()?\\s*(['\"]?)([^)'\";]+)\\1", Pattern.CASE_INSENSITIVE);

    public EpubResourceGraph analyze(Path epub) throws Exception {
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            validateEntryNames(zip);
            String packagePath = packagePath(zip);
            PackageData packageData = parsePackage(zip, packagePath);
            Set<String> encrypted = encryptedResources(zip);
            Map<String, EpubResourceNode> nodes = new LinkedHashMap<>();
            List<EpubResourceReference> references = new ArrayList<>();
            Set<String> roots = new LinkedHashSet<>();
            roots.add("mimetype");
            roots.add("META-INF/container.xml");
            roots.add(packagePath);
            List<String> warnings = new ArrayList<>();

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled();
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                ManifestItem item = packageData.byPath.get(entry.getName());
                boolean spine = item != null && packageData.spineIds.contains(item.id);
                boolean nav = item != null && item.properties.contains("nav");
                nodes.put(entry.getName(), new EpubResourceNode(entry.getName(),
                        item == null ? mediaTypeFromPath(entry.getName()) : item.mediaType,
                        entry.getSize(), sha256(zip, entry), item != null, spine, nav,
                        encrypted.contains(entry.getName()), item == null ? Set.of() : item.properties));
                if (spine || nav) roots.add(entry.getName());
            }

            for (String spineId : packageData.spineIds) {
                if (!packageData.byId.containsKey(spineId)) {
                    throw new IllegalArgumentException("EPUB spine idref is missing from manifest: " + spineId);
                }
            }
            for (ManifestItem item : packageData.items) {
                if (!nodes.containsKey(item.path)) {
                    throw new IllegalArgumentException("EPUB manifest resource is missing from ZIP: " + item.path);
                }
                references.add(new EpubResourceReference(packagePath, item.path, EpubReferenceType.MANIFEST));
                if (packageData.spineIds.contains(item.id)) {
                    references.add(new EpubResourceReference(packagePath, item.path, EpubReferenceType.SPINE));
                }
                if (item.properties.contains("nav")) {
                    references.add(new EpubResourceReference(packagePath, item.path, EpubReferenceType.NAVIGATION));
                }
                if (item.properties.contains("cover-image")) {
                    roots.add(item.path);
                    references.add(new EpubResourceReference(packagePath, item.path, EpubReferenceType.COVER));
                }
                if (item.fallbackId != null && !item.fallbackId.isBlank()) {
                    ManifestItem fallback = packageData.byId.get(item.fallbackId);
                    if (fallback != null) references.add(new EpubResourceReference(item.path, fallback.path, EpubReferenceType.FALLBACK));
                    else warnings.add("Broken OPF fallback reference: " + item.id + " -> " + item.fallbackId);
                }
                if (item.mediaOverlayId != null && !item.mediaOverlayId.isBlank()) {
                    ManifestItem overlay = packageData.byId.get(item.mediaOverlayId);
                    if (overlay != null) references.add(new EpubResourceReference(item.path, overlay.path, EpubReferenceType.OTHER));
                    else warnings.add("Broken OPF media-overlay reference: " + item.id + " -> " + item.mediaOverlayId);
                }
            }
            if (packageData.spineTocId != null && !packageData.spineTocId.isBlank()) {
                ManifestItem toc = packageData.byId.get(packageData.spineTocId);
                if (toc != null) {
                    roots.add(toc.path);
                    references.add(new EpubResourceReference(packagePath, toc.path, EpubReferenceType.NAVIGATION));
                } else warnings.add("Broken OPF spine toc reference: " + packageData.spineTocId);
            }
            for (String guidePath : packageData.guidePaths) {
                if (nodes.containsKey(guidePath)) {
                    roots.add(guidePath);
                    references.add(new EpubResourceReference(packagePath, guidePath, EpubReferenceType.OTHER));
                } else warnings.add("Broken EPUB guide reference: " + guidePath);
            }
            if (packageData.coverId != null) {
                ManifestItem cover = packageData.byId.get(packageData.coverId);
                if (cover != null) {
                    roots.add(cover.path);
                    references.add(new EpubResourceReference(packagePath, cover.path, EpubReferenceType.COVER));
                } else warnings.add("Broken EPUB2 cover meta reference: " + packageData.coverId);
            }

            for (EpubResourceNode node : nodes.values()) {
                if (node.encrypted()) continue;
                String media = node.mediaType() == null ? "" : node.mediaType().toLowerCase(Locale.ROOT);
                if (media.contains("xhtml") || media.equals("text/html") || node.path().toLowerCase(Locale.ROOT).endsWith(".xhtml")) {
                    parseMarkupRefs(zip, node.path(), references, warnings);
                } else if (media.equals("text/css") || node.path().toLowerCase(Locale.ROOT).endsWith(".css")) {
                    parseCssRefs(zip, node.path(), references, warnings);
                } else if (media.contains("svg") || media.contains("smil") || media.contains("dtbncx")
                        || node.path().toLowerCase(Locale.ROOT).endsWith(".svg")
                        || node.path().toLowerCase(Locale.ROOT).endsWith(".smil")
                        || node.path().toLowerCase(Locale.ROOT).endsWith(".ncx")) {
                    parseMarkupRefs(zip, node.path(), references, warnings);
                }
            }
            references.removeIf(ref -> ref.toPath() == null || ref.toPath().isBlank());
            for (EpubResourceReference reference : references) {
                if (!nodes.containsKey(reference.toPath())) {
                    warnings.add("Broken internal EPUB reference: " + reference.fromPath() + " -> " + reference.toPath());
                }
            }
            return new EpubResourceGraph(packagePath, nodes, references, roots, warnings);
        }
    }

    private void validateEntryNames(ZipFile zip) throws Exception {
        Set<String> seen = new HashSet<>();
        Enumeration<? extends ZipEntry> e = zip.entries();
        int count = 0;
        long total = 0L;
        while (e.hasMoreElements()) {
            checkCancelled();
            ZipEntry entry = e.nextElement();
            String name = entry.getName();
            if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IllegalArgumentException("EPUB contains too many ZIP entries");
            if (unsafe(name)) throw new IllegalArgumentException("Unsafe EPUB ZIP path: " + name);
            if (!seen.add(name)) throw new IllegalArgumentException("Duplicate EPUB ZIP entry: " + name);
            if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                throw new IllegalArgumentException("Unsupported EPUB ZIP compression method: " + name);
            }
            if (entry.isDirectory()) continue;
            long size = entry.getSize();
            if (size < 0) throw new IllegalArgumentException("EPUB ZIP entry has unknown uncompressed size: " + name);
            if (ArchiveSafetyLimits.declaredEntryTooLarge(size)) throw new IllegalArgumentException("EPUB ZIP entry is too large: " + name);
            if (Long.MAX_VALUE - total < size || (total += size) > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                throw new IllegalArgumentException("EPUB uncompressed payload exceeds safety limit");
            }
            long compressed = entry.getCompressedSize();
            if (ArchiveSafetyLimits.exceedsCompressionRatio(size, compressed)) {
                throw new IllegalArgumentException("Suspicious EPUB ZIP compression ratio: " + name);
            }
        }
    }

    private String packagePath(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) throw new IllegalArgumentException("EPUB container.xml is missing");
        try (InputStream in = zip.getInputStream(container)) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    checkCancelled();
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "rootfile".equals(reader.getLocalName())) {
                        String value = reader.getAttributeValue(null, "full-path");
                        if (value != null && !value.isBlank() && zip.getEntry(value) != null && !unsafe(value)) return value;
                    }
                }
            } finally { reader.close(); }
        }
        throw new IllegalArgumentException("EPUB package document cannot be resolved");
    }

    private PackageData parsePackage(ZipFile zip, String packagePath) throws Exception {
        List<ManifestItem> items = new ArrayList<>();
        Set<String> spine = new LinkedHashSet<>();
        String coverId = null;
        String spineTocId = null;
        List<String> guidePaths = new ArrayList<>();
        String base = parent(packagePath);
        try (InputStream in = zip.getInputStream(zip.getEntry(packagePath))) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    checkCancelled();
                    int event = reader.next();
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    if ("item".equals(reader.getLocalName())) {
                        String id = attr(reader, "id");
                        String href = attr(reader, "href");
                        if (id == null || href == null) continue;
                        String path = resolve(base, href);
                        Set<String> properties = tokens(attr(reader, "properties"));
                        items.add(new ManifestItem(id, path, Objects.toString(attr(reader, "media-type"), ""),
                                properties, attr(reader, "fallback"), attr(reader, "media-overlay")));
                    } else if ("spine".equals(reader.getLocalName())) {
                        spineTocId = attr(reader, "toc");
                    } else if ("itemref".equals(reader.getLocalName())) {
                        String idref = attr(reader, "idref");
                        if (idref != null) spine.add(idref);
                    } else if ("reference".equals(reader.getLocalName())) {
                        String href = attr(reader, "href");
                        if (href != null && !href.isBlank()) guidePaths.add(resolve(base, href));
                    } else if ("meta".equals(reader.getLocalName())) {
                        String name = attr(reader, "name");
                        if ("cover".equalsIgnoreCase(name)) coverId = attr(reader, "content");
                    }
                }
            } finally { reader.close(); }
        }
        Map<String, ManifestItem> byId = new LinkedHashMap<>();
        Map<String, ManifestItem> byPath = new LinkedHashMap<>();
        for (ManifestItem item : items) {
            if (byId.putIfAbsent(item.id, item) != null) throw new IllegalArgumentException("Duplicate EPUB manifest id: " + item.id);
            byPath.putIfAbsent(item.path, item);
        }
        return new PackageData(items, byId, byPath, spine, coverId, spineTocId, List.copyOf(guidePaths));
    }

    private Set<String> encryptedResources(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("META-INF/encryption.xml");
        if (entry == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    checkCancelled();
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "CipherReference".equals(reader.getLocalName())) {
                        String uri = attr(reader, "URI");
                        if (uri != null) result.add(resolve("", uri));
                    }
                }
            } finally { reader.close(); }
        }
        return Set.copyOf(result);
    }

    private void parseMarkupRefs(ZipFile zip, String path, List<EpubResourceReference> refs, List<String> warnings) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return;
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(in);
            String base = parent(path);
            try {
                while (reader.hasNext()) {
                    checkCancelled();
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        String local = reader.getAttributeLocalName(i);
                        String raw = reader.getAttributeValue(i);
                        if ("style".equals(local)) {
                            addCssMatches(path, base, raw, CSS_URL, EpubReferenceType.CSS_URL, refs);
                            continue;
                        }
                        if ("srcset".equals(local)) {
                            for (String candidate : raw.split(",")) {
                                String token = candidate.trim().split("\\s+", 2)[0];
                                String target = resolveReference(base, token);
                                if (target != null) refs.add(new EpubResourceReference(path, target, EpubReferenceType.XHTML_SRC));
                            }
                            continue;
                        }
                        if (!("href".equals(local) || "src".equals(local) || "poster".equals(local) || "data".equals(local))) continue;
                        String target = resolveReference(base, raw);
                        if (target == null) continue;
                        EpubReferenceType type = "href".equals(local) ? EpubReferenceType.XHTML_HREF : EpubReferenceType.XHTML_SRC;
                        refs.add(new EpubResourceReference(path, target, type));
                    }
                }
            } finally { reader.close(); }
        } catch (Exception e) {
            warnings.add("Cannot parse references in " + path + ": " + e.getClass().getSimpleName());
        }
    }

    private void parseCssRefs(ZipFile zip, String path, List<EpubResourceReference> refs, List<String> warnings) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return;
        if (entry.getSize() < 0L || entry.getSize() > MAX_CSS_SCAN_BYTES) {
            warnings.add("CSS resource exceeds safe reference-analysis limit: " + path);
            return;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            String css = new String(readBounded(in, MAX_CSS_SCAN_BYTES, path), StandardCharsets.UTF_8);
            String base = parent(path);
            addCssMatches(path, base, css, CSS_URL, EpubReferenceType.CSS_URL, refs);
            addCssMatches(path, base, css, CSS_IMPORT, EpubReferenceType.CSS_IMPORT, refs);
        } catch (Exception e) {
            warnings.add("Cannot parse CSS references in " + path + ": " + e.getClass().getSimpleName());
        }
    }

    private byte[] readBounded(InputStream input, long limit, String path) throws Exception {
        if (limit > Integer.MAX_VALUE - 1L) throw new IllegalArgumentException("Invalid CSS scan limit");
        byte[] bytes = input.readNBytes((int) limit + 1);
        checkCancelled();
        if (bytes.length > limit) throw new IllegalArgumentException("CSS resource exceeds safe scan limit: " + path);
        return bytes;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("EPUB resource graph analysis cancelled");
    }

    private void addCssMatches(String from, String base, String css, Pattern pattern, EpubReferenceType type,
                               List<EpubResourceReference> refs) {
        Matcher matcher = pattern.matcher(css);
        while (matcher.find()) {
            String target = resolveReference(base, matcher.group(2));
            if (target != null) refs.add(new EpubResourceReference(from, target, type));
        }
    }

    private String resolveReference(String base, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("data:")
                || lower.startsWith("mailto:") || lower.startsWith("javascript:") || value.startsWith("#")) return null;
        int hash = value.indexOf('#'); if (hash >= 0) value = value.substring(0, hash);
        int query = value.indexOf('?'); if (query >= 0) value = value.substring(0, query);
        if (value.isBlank()) return null;
        return resolve(base, decodePercent(value));
    }

    private String decodePercent(String value) {
        if (value == null || value.indexOf('%') < 0) return value;
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) != '%' || i + 2 >= value.length() || hex(value.charAt(i + 1)) < 0 || hex(value.charAt(i + 2)) < 0) {
                result.append(value.charAt(i++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i + 2 < value.length() && value.charAt(i) == '%' && hex(value.charAt(i + 1)) >= 0 && hex(value.charAt(i + 2)) >= 0) {
                bytes.write((hex(value.charAt(i + 1)) << 4) | hex(value.charAt(i + 2)));
                i += 3;
            }
            result.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + c - 'a';
        if (c >= 'A' && c <= 'F') return 10 + c - 'A';
        return -1;
    }

    static String resolve(String base, String relative) {
        String joined = (base == null || base.isBlank() ? "" : base + "/") + relative.replace('\\', '/');
        Deque<String> parts = new ArrayDeque<>();
        for (String part : joined.split("/")) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) { if (!parts.isEmpty()) parts.removeLast(); else return ""; }
            else parts.addLast(part);
        }
        return String.join("/", parts);
    }

    private String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private String attr(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) return reader.getAttributeValue(i);
        }
        return null;
    }

    private Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.trim().split("\\s+")) if (!token.isBlank()) result.add(token);
        return Set.copyOf(result);
    }

    private String sha256(ZipFile zip, ZipEntry entry) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) >= 0;) {
                checkCancelled();
                if (n > 0) digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean unsafe(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) return true;
        for (String part : name.replace('\\','/').split("/")) if ("..".equals(part)) return true;
        return false;
    }

    private String mediaTypeFromPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) return "application/xhtml+xml";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private record ManifestItem(String id, String path, String mediaType, Set<String> properties, String fallbackId,
                                String mediaOverlayId) { }
    private record PackageData(List<ManifestItem> items, Map<String, ManifestItem> byId,
                               Map<String, ManifestItem> byPath, Set<String> spineIds, String coverId,
                               String spineTocId, List<String> guidePaths) { }
}
