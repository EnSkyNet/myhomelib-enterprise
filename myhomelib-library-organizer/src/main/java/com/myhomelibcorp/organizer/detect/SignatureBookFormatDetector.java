package com.myhomelibcorp.organizer.detect;

import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Detects the physical book format from signatures/container structure before trusting the extension. */
@Component
public final class SignatureBookFormatDetector {
    private static final int PROBE_BYTES = 8192;

    public DetectedBookFormat detect(Path source) {
        if (source == null) return unknown("Path is null", "");
        Path file = source.toAbsolutePath().normalize();
        String extension = extension(file.getFileName().toString());
        try {
            file = OrganizerPathSafety.canonicalExistingFile(file, "Organizer format detection source");
            byte[] probe = readProbe(file);
            if (startsWith(probe, "%PDF-".getBytes(StandardCharsets.US_ASCII)))
                return new DetectedBookFormat("pdf", "application/pdf", DetectionConfidence.CERTAIN, extension, false, "PDF magic");
            if (probe.length >= 4 && probe[0] == 'P' && probe[1] == 'K' && probe[2] == 3 && probe[3] == 4)
                return detectZip(file, extension);
            if (looksLikeMobi(probe))
                return new DetectedBookFormat(mobiFormat(extension), "application/x-mobipocket-ebook", DetectionConfidence.HIGH, extension, false, "PalmDB/MOBI signature");
            DetectedBookFormat xmlFormat = detectXml(file, extension);
            if (xmlFormat != null) return xmlFormat;
            if ("txt".equals(extension) || "md".equals(extension) || "markdown".equals(extension))
                return new DetectedBookFormat(extension.equals("txt") ? "txt" : "markdown", "text/plain", DetectionConfidence.LOW, extension, false, "Text extension fallback");
            if ("rtf".equals(extension) && startsWith(probe, "{\\rtf".getBytes(StandardCharsets.US_ASCII)))
                return new DetectedBookFormat("rtf", "application/rtf", DetectionConfidence.HIGH, extension, false, "RTF signature");
            return extensionFallback(extension);
        } catch (Exception failure) {
            return unknown("Detection failed: " + failure.getClass().getSimpleName(), extension);
        }
    }

    private DetectedBookFormat detectZip(Path file, String extension) throws Exception {
        try (ZipFile zip = SafeZipSupport.openDetected(file, "Organizer signature archive")) {
            int fb2 = 0, html = 0, books = 0, inspected = 0;
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                if (++inspected > ArchiveSafetyLimits.MAX_ENTRY_COUNT)
                    return unknown("ZIP exceeds archive entry-count safety limit", extension);
                ZipArchiveEntry entry = entries.nextElement();
                if (unsafe(entry.getName())) return unknown("ZIP contains an unsafe entry path", extension);
                if (entry.isDirectory()) continue;
                if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize()))
                    return unknown("ZIP contains an oversized entry", extension);
                long compressed = entry.getCompressedSize(), size = entry.getSize();
                if (compressed > 0 && size > 0 && exceedsCompressionRatio(size, compressed))
                    return unknown("ZIP contains a suspicious compression ratio", extension);
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".fb2")) fb2++;
                if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")) html++;
                if (isBookEntryName(lower)) books++;
            }

            ZipArchiveEntry mimetype = zip.getEntry("mimetype");
            if (mimetype != null && !mimetype.isDirectory()) {
                try (InputStream in = zip.getInputStream(mimetype)) {
                    String value = new String(in.readNBytes(128), StandardCharsets.US_ASCII).trim();
                    if ("application/epub+zip".equals(value))
                        return new DetectedBookFormat("epub", "application/epub+zip", DetectionConfidence.CERTAIN, extension, true, "EPUB mimetype");
                    if ("application/vnd.oasis.opendocument.text".equals(value))
                        return new DetectedBookFormat("odt", value, DetectionConfidence.CERTAIN, extension, true, "ODT mimetype");
                }
            }
            if (zip.getEntry("[Content_Types].xml") != null && zip.getEntry("word/document.xml") != null)
                return new DetectedBookFormat("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DetectionConfidence.CERTAIN, extension, true, "DOCX package structure");
            if (fb2 == 1 && books == 1)
                return new DetectedBookFormat("fb2_zip", "application/zip", DetectionConfidence.HIGH, extension, true, "ZIP contains one FB2 payload");
            if ("htmlz".equals(extension) && html > 0 && fb2 == 0)
                return new DetectedBookFormat("htmlz", "application/zip", DetectionConfidence.HIGH, extension, true, "HTMLZ ZIP contains HTML content");
            if (books > 1)
                return new DetectedBookFormat("book_archive", "application/zip", DetectionConfidence.HIGH, extension, true, "Archive contains multiple book payloads");
            if ("cbz".equals(extension) || "cbc".equals(extension) || "txtz".equals(extension) || "pmlz".equals(extension))
                return new DetectedBookFormat(extension, "application/zip", DetectionConfidence.HIGH, extension, true,
                        "ZIP container confirmed for legacy/comic format");
            return new DetectedBookFormat("zip", "application/zip", DetectionConfidence.HIGH, extension, true, "ZIP signature");
        }
    }


    private boolean isBookEntryName(String lower) {
        return BookEntryNameSupport.isSupportedBookEntry(lower);
    }

    private boolean exceedsCompressionRatio(long uncompressed, long compressed) {
        if (compressed <= 0 || uncompressed <= 0) return false;
        if (compressed > Long.MAX_VALUE / ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) return false;
        return uncompressed > compressed * ArchiveSafetyLimits.MAX_COMPRESSION_RATIO;
    }

    private DetectedBookFormat detectXml(Path file, String extension) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        String local = reader.getLocalName();
                        if ("FictionBook".equals(local))
                            return new DetectedBookFormat("fb2", "application/x-fictionbook+xml", DetectionConfidence.CERTAIN, extension, false, "FictionBook XML root");
                        if ("html".equalsIgnoreCase(local))
                            return new DetectedBookFormat("html", "text/html", DetectionConfidence.HIGH, extension, false, "HTML root element");
                        return null;
                    }
                }
            } finally { reader.close(); }
        } catch (Exception ignored) { }
        return null;
    }

    private DetectedBookFormat extensionFallback(String extension) {
        return switch (extension) {
            case "epub" -> new DetectedBookFormat("epub", "application/epub+zip", DetectionConfidence.LOW, extension, true, "Extension fallback; container signature not confirmed");
            case "fb2" -> new DetectedBookFormat("fb2", "application/x-fictionbook+xml", DetectionConfidence.LOW, extension, false, "Extension fallback; XML root not confirmed");
            case "mobi", "prc", "azw", "azw3", "azw4" -> new DetectedBookFormat(extension, "application/x-mobipocket-ebook", DetectionConfidence.LOW, extension, false, "Extension fallback");
            case "docx" -> new DetectedBookFormat("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DetectionConfidence.LOW, extension, true, "Extension fallback; package structure not confirmed");
            case "odt" -> new DetectedBookFormat("odt", "application/vnd.oasis.opendocument.text", DetectionConfidence.LOW, extension, true, "Extension fallback");
            case "html", "htm" -> new DetectedBookFormat("html", "text/html", DetectionConfidence.LOW, extension, false, "Extension fallback");
            case "htmlz" -> new DetectedBookFormat("htmlz", "application/zip", DetectionConfidence.LOW, extension, true, "HTMLZ extension fallback; ZIP content not confirmed");
            case "rtf" -> new DetectedBookFormat("rtf", "application/rtf", DetectionConfidence.LOW, extension, false, "Extension fallback");
            case "chm" -> new DetectedBookFormat("chm", "application/vnd.ms-htmlhelp", DetectionConfidence.LOW, extension, false, "Legacy format extension fallback");
            case "djvu", "djv" -> new DetectedBookFormat("djvu", "image/vnd.djvu", DetectionConfidence.LOW, extension, false, "Legacy format extension fallback");
            case "cbz", "cbc" -> new DetectedBookFormat(extension, "application/zip", DetectionConfidence.LOW, extension, true, "Comic archive extension fallback");
            case "cbr" -> new DetectedBookFormat("cbr", "application/vnd.rar", DetectionConfidence.LOW, extension, true, "Comic archive extension fallback");
            case "cb7" -> new DetectedBookFormat("cb7", "application/x-7z-compressed", DetectionConfidence.LOW, extension, true, "Comic archive extension fallback");
            case "lit", "lrf", "pdb", "pml", "pmlz", "rb", "snb", "tcr", "txtz" ->
                    new DetectedBookFormat(extension, "application/octet-stream", DetectionConfidence.LOW, extension,
                            "pmlz".equals(extension) || "txtz".equals(extension), "Legacy format extension fallback");
            default -> unknown("No recognized signature", extension);
        };
    }

    private byte[] readProbe(Path file) throws Exception {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Unsafe/non-regular source");
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { return in.readNBytes(PROBE_BYTES); }
    }
    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }
    private boolean looksLikeMobi(byte[] bytes) {
        if (bytes.length < 78) return false;
        String typeCreator = new String(bytes, 60, Math.min(8, bytes.length - 60), StandardCharsets.US_ASCII);
        return typeCreator.startsWith("BOOKMOBI") || typeCreator.startsWith("TEXtREAd");
    }
    private String mobiFormat(String extension) { return switch (extension) { case "azw", "azw3", "azw4", "prc" -> extension; default -> "mobi"; }; }
    private String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private boolean unsafe(String name) { if (name == null || name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) return true; for (String p : name.replace('\\','/').split("/")) if ("..".equals(p)) return true; return false; }
    private DetectedBookFormat unknown(String reason, String extension) { return new DetectedBookFormat("unknown", "application/octet-stream", DetectionConfidence.UNKNOWN, extension, false, reason); }
}
