package com.myhomelibcorp.converter.toolchain.validation;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

@Component
public class EpubOutputValidator implements ConversionOutputValidator {

    @Override public boolean supports(String format) { return "epub".equalsIgnoreCase(format) || "kepub".equalsIgnoreCase(format); }

    @Override
    public void validate(Path file, long maxOutputBytes) throws ConversionToolchainException {
        ValidationSupport.validateRegularFile(file, maxOutputBytes);
        validateFirstEntry(file);
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry mimetype = zip.getEntry("mimetype");
            ZipEntry container = zip.getEntry("META-INF/container.xml");
            if (mimetype == null || container == null) invalid("EPUB is missing mimetype or META-INF/container.xml");
            if (mimetype.getMethod() != ZipEntry.STORED) invalid("EPUB mimetype must use ZIP Store");
            if (mimetype.getExtra() != null && mimetype.getExtra().length > 0) invalid("EPUB mimetype must not contain ZIP extra fields");
            try (InputStream in = zip.getInputStream(mimetype)) {
                byte[] bytes = in.readNBytes("application/epub+zip".length() + 1);
                String value = new String(bytes, StandardCharsets.US_ASCII);
                if (bytes.length != "application/epub+zip".length() || !"application/epub+zip".equals(value))
                    invalid("Invalid EPUB mimetype");
            }
            String opfPath;
            try (InputStream in = zip.getInputStream(container)) { opfPath = findRootfile(in); }
            if (opfPath == null || ValidationSupport.unsafeZipName(opfPath) || zip.getEntry(opfPath) == null) {
                invalid("EPUB package document is missing or unsafe");
            }
            validateArchiveEntries(zip);
            validatePackage(zip, opfPath);
        } catch (UnsafeZipEntry e) {
            invalid("EPUB contains an unsafe ZIP entry: " + e.getMessage());
        } catch (ConversionToolchainException e) {
            throw e;
        } catch (Exception e) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                    "EPUB output cannot be parsed", e);
        }
    }

    private void validateFirstEntry(Path file) throws ConversionToolchainException {
        try (ZipInputStream in = new ZipInputStream(java.nio.file.Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8)) {
            ZipEntry first = in.getNextEntry();
            if (first == null || !"mimetype".equals(first.getName())) invalid("EPUB mimetype must be the first ZIP entry");
            if (first.getMethod() != ZipEntry.STORED) invalid("EPUB mimetype must use ZIP Store");
            if (first.getExtra() != null && first.getExtra().length > 0) invalid("EPUB mimetype must not contain ZIP extra fields");
        } catch (ConversionToolchainException e) {
            throw e;
        } catch (Exception e) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                    "EPUB first ZIP entry cannot be parsed", e);
        }
    }

    private String findRootfile(InputStream input) throws Exception {
        XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(input);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && "rootfile".equals(reader.getLocalName())) {
                    String path = reader.getAttributeValue(null, "full-path");
                    if (path != null && !path.isBlank()) return path;
                }
            }
            return null;
        } finally { reader.close(); }
    }

    private void validateArchiveEntries(ZipFile zip) throws ConversionToolchainException {
        Set<String> seen = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0;
        long totalUncompressed = 0L;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) invalid("EPUB contains too many ZIP entries");
            if (ValidationSupport.unsafeZipName(name)) throw new UnsafeZipEntry(name);
            if (!seen.add(name)) invalid("EPUB contains a duplicate ZIP entry: " + name);
            if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                invalid("EPUB uses an unsupported ZIP compression method: " + name);
            }
            if (entry.isDirectory()) continue;
            long size = entry.getSize();
            if (size < 0) invalid("EPUB ZIP entry has unknown uncompressed size: " + name);
            if (ArchiveSafetyLimits.declaredEntryTooLarge(size)) invalid("EPUB ZIP entry exceeds the safety limit: " + name);
            if (Long.MAX_VALUE - totalUncompressed < size
                    || (totalUncompressed += size) > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                invalid("EPUB uncompressed payload exceeds the safety limit");
            }
            long compressed = entry.getCompressedSize();
            if (ArchiveSafetyLimits.exceedsCompressionRatio(size, compressed)) {
                invalid("EPUB ZIP entry has a suspicious compression ratio: " + name);
            }
        }
    }

    private void validatePackage(ZipFile zip, String opfPath) throws Exception {
        ZipEntry opf = zip.getEntry(opfPath);
        Set<String> manifestIds = new HashSet<>();
        Set<String> manifestPaths = new HashSet<>();
        Set<String> spineRefs = new HashSet<>();
        boolean packageRoot = false;
        String base = parent(opfPath);
        try (InputStream input = zip.getInputStream(opf)) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(input);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    switch (reader.getLocalName()) {
                        case "package" -> packageRoot = true;
                        case "item" -> {
                            String id = reader.getAttributeValue(null, "id");
                            String href = reader.getAttributeValue(null, "href");
                            if (id == null || id.isBlank() || href == null || href.isBlank()) {
                                invalid("EPUB manifest contains an item without id/href");
                            }
                            if (!manifestIds.add(id)) invalid("EPUB manifest contains a duplicate id: " + id);
                            String resource = resolveInternal(base, href);
                            if (resource == null || resource.isBlank() || ValidationSupport.unsafeZipName(resource)) {
                                invalid("EPUB manifest contains an unsafe href: " + href);
                            }
                            if (!manifestPaths.add(resource)) invalid("EPUB manifest contains a duplicate resource href: " + href);
                            ZipEntry resourceEntry = zip.getEntry(resource);
                            if (resourceEntry == null || resourceEntry.isDirectory()) {
                                invalid("EPUB manifest resource is missing from ZIP: " + resource);
                            }
                        }
                        case "itemref" -> {
                            String idref = reader.getAttributeValue(null, "idref");
                            if (idref == null || idref.isBlank()) invalid("EPUB spine contains an empty idref");
                            spineRefs.add(idref);
                        }
                    }
                }
            } finally { reader.close(); }
        }
        if (!packageRoot || manifestIds.isEmpty() || spineRefs.isEmpty() || !manifestIds.containsAll(spineRefs)) {
            invalid("EPUB OPF manifest/spine is incomplete or inconsistent");
        }
    }

    private String resolveInternal(String base, String raw) {
        String value = raw == null ? "" : raw.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("data:")
                || lower.startsWith("mailto:") || lower.startsWith("javascript:") || value.startsWith("#")) return null;
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        if (value.isBlank()) return null;
        value = decodePercent(value).replace('\\', '/');
        String joined = (base == null || base.isBlank() ? "" : base + "/") + value;
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (String part : joined.split("/")) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    private String decodePercent(String value) {
        if (value.indexOf('%') < 0) return value;
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            if (value.charAt(i) != '%' || i + 2 >= value.length()
                    || hex(value.charAt(i + 1)) < 0 || hex(value.charAt(i + 2)) < 0) {
                result.append(value.charAt(i++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i + 2 < value.length() && value.charAt(i) == '%'
                    && hex(value.charAt(i + 1)) >= 0 && hex(value.charAt(i + 2)) >= 0) {
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

    private String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static void invalid(String message) throws ConversionToolchainException {
        throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, message);
    }

    private static final class UnsafeZipEntry extends RuntimeException {
        UnsafeZipEntry(String name) { super(name); }
    }
}
