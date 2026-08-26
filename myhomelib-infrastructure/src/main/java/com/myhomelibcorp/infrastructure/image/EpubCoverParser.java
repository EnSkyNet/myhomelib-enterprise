package com.myhomelibcorp.infrastructure.image;

import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public class EpubCoverParser {
    private static final int MAX_XML = 2 * 1024 * 1024;
    private static final int MAX_COVER = 24 * 1024 * 1024;

    public byte[] parse(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            String rootFile = rootFile(zip);
            if (rootFile == null) return firstLikelyCover(zip);
            ZipEntry opfEntry = zip.getEntry(rootFile);
            if (opfEntry == null) return firstLikelyCover(zip);
            String opf;
            try (InputStream in = zip.getInputStream(opfEntry)) {
                opf = new String(readBounded(in, MAX_XML), StandardCharsets.UTF_8);
            }
            String href = coverHref(opf);
            if (href != null) {
                String base = rootFile.contains("/") ? rootFile.substring(0, rootFile.lastIndexOf('/') + 1) : "";
                String resolved = normalize(base + decode(href));
                ZipEntry cover = findEntry(zip, resolved);
                if (cover != null && !cover.isDirectory()) {
                    try (InputStream in = zip.getInputStream(cover)) {
                        return readBounded(in, MAX_COVER);
                    }
                }
            }
            return firstLikelyCover(zip);
        }
    }

    private static String rootFile(ZipFile zip) throws IOException {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) return null;
        String xml;
        try (InputStream in = zip.getInputStream(container)) {
            xml = new String(readBounded(in, MAX_XML), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? normalize(decode(m.group(1))) : null;
    }

    private static String coverHref(String opf) {
        Matcher property = Pattern.compile("<item\\b[^>]*properties\\s*=\\s*[\"'][^\"']*\\bcover-image\\b[^\"']*[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
        if (property.find()) {
            String href = attr(property.group(), "href");
            if (href != null) return href;
        }
        Matcher meta = Pattern.compile("<meta\\b[^>]*name\\s*=\\s*[\"']cover[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
        if (meta.find()) {
            String id = attr(meta.group(), "content");
            if (id != null) {
                Matcher item = Pattern.compile("<item\\b[^>]*id\\s*=\\s*[\"']" + Pattern.quote(id) + "[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
                if (item.find()) return attr(item.group(), "href");
            }
        }
        Matcher guide = Pattern.compile("<reference\\b[^>]*type\\s*=\\s*[\"']cover[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
        if (guide.find()) return attr(guide.group(), "href");
        return null;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] firstLikelyCover(ZipFile zip) throws IOException {
        ZipEntry firstImage = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0;
        while (entries.hasMoreElements() && count++ < ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String lower = entry.getName().toLowerCase(Locale.ROOT);
            if (!lower.matches(".*\\.(jpe?g|png|gif|webp)$")) continue;
            if (firstImage == null) firstImage = entry;
            if (lower.contains("cover") || lower.contains("title")) {
                try (InputStream in = zip.getInputStream(entry)) { return readBounded(in, MAX_COVER); }
            }
        }
        if (firstImage != null) {
            try (InputStream in = zip.getInputStream(firstImage)) { return readBounded(in, MAX_COVER); }
        }
        return null;
    }

    private static ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry direct = zip.getEntry(name);
        if (direct != null) return direct;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (normalize(e.getName()).equalsIgnoreCase(name)) return e;
        }
        return null;
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 64 * 1024));
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > max) throw new IOException("EPUB resource exceeds safe cover limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); } catch (Exception e) { return value; }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : value.replace('\\', '/').split("/")) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.addLast(part);
        }
        return String.join("/", parts);
    }
}
