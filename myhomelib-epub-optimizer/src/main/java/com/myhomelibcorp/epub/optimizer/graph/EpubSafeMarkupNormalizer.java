package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Conservative EPUB text cleanup. It never rewrites the XML tree: XML-like resources only receive
 * XML-equivalent line-ending normalization. CSS comments are removed only outside strings and only
 * when they are not license/source-map/protected comments; a single space is retained to prevent
 * accidental token concatenation.
 */
@Component
public final class EpubSafeMarkupNormalizer {
    private static final byte[] MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final long MAX_TEXT_RESOURCE = 16L * 1024L * 1024L;

    public Result normalize(Path source, Path target, EpubResourceGraph graph) throws Exception {
        Objects.requireNonNull(graph, "graph");
        source = EpubPathSafety.requireSafeRegularFile(source, "EPUB markup source");
        target = EpubPathSafety.prepareTarget(target, "EPUB markup target");
        if (!graph.warnings().isEmpty() || hasScripting(graph)) {
            EpubPathSafety.copyFreshFile(source, target, "EPUB markup passthrough");
            return Result.none();
        }
        int changed = 0;
        long before = 0L, after = 0L;
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(EpubPathSafety.openFreshOutputStream(target, "EPUB markup target"), StandardCharsets.UTF_8)) {
            out.setLevel(9);
            writeMimetype(out);
            List<? extends ZipEntry> entries = Collections.list(zip.entries()).stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> !"mimetype".equals(entry.getName()))
                    .sorted(Comparator.comparingInt((ZipEntry entry) -> "META-INF/container.xml".equals(entry.getName()) ? 0 : 1)
                            .thenComparing(ZipEntry::getName))
                    .toList();
            byte[] buffer = new byte[64 * 1024];
            for (ZipEntry entry : entries) {
                checkCancelled();
                byte[] replacement = null;
                EpubResourceNode node = graph.nodes().get(entry.getName());
                if (entry.getSize() >= 0 && entry.getSize() <= MAX_TEXT_RESOURCE && isTextCandidate(entry.getName(), node)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] original = in.readNBytes((int) MAX_TEXT_RESOURCE + 1);
                        checkCancelled();
                        if (original.length > MAX_TEXT_RESOURCE) {
                            replacement = null;
                        } else {
                            replacement = normalizeBytes(original, entry.getName(), node);
                        }
                        if (replacement != null && !Arrays.equals(original, replacement)) {
                            changed++;
                            before += original.length;
                            after += replacement.length;
                        } else replacement = null;
                    }
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTimeLocal(ZIP_TIME);
                copy.setExtra(null);
                copy.setMethod(ZipEntry.DEFLATED);
                out.putNextEntry(copy);
                if (replacement != null) out.write(replacement);
                else try (InputStream in = zip.getInputStream(entry)) {
                    for (int n; (n = in.read(buffer)) >= 0;) { checkCancelled(); if (n > 0) out.write(buffer, 0, n); }
                }
                out.closeEntry();
            }
        }
        return new Result(changed, before, after);
    }

    private byte[] normalizeBytes(byte[] original, String path, EpubResourceNode node) {
        String text;
        try { text = decodeUtf8(original); }
        catch (CharacterCodingException unsupportedEncoding) { return null; }
        String normalized = normalizeLineEndings(text);
        if (isCss(path, node)) normalized = stripSafeCssComments(normalized);
        return normalized.equals(text) ? null : normalized.getBytes(StandardCharsets.UTF_8);
    }

    private String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
        String value = chars.toString();
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private String normalizeLineEndings(String value) {
        return value.indexOf('\r') < 0 ? value : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String stripSafeCssComments(String css) {
        StringBuilder out = new StringBuilder(css.length());
        boolean single = false, dbl = false, escaped = false;
        for (int i = 0; i < css.length();) {
            char c = css.charAt(i);
            if (escaped) { out.append(c); escaped = false; i++; continue; }
            if ((single || dbl) && c == '\\') { out.append(c); escaped = true; i++; continue; }
            if (!dbl && c == '\'') { single = !single; out.append(c); i++; continue; }
            if (!single && c == '"') { dbl = !dbl; out.append(c); i++; continue; }
            if (!single && !dbl && c == '/' && i + 1 < css.length() && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                if (end < 0) { out.append(css, i, css.length()); break; }
                String comment = css.substring(i, end + 2);
                if (protectedComment(comment)) out.append(comment);
                else if (needsSeparator(out, css, end + 2)) out.append(' ');
                i = end + 2;
                continue;
            }
            out.append(c); i++;
        }
        return out.toString();
    }

    private boolean protectedComment(String comment) {
        String lower = comment.toLowerCase(Locale.ROOT);
        return comment.startsWith("/*!") || comment.startsWith("/*#") || lower.contains("@license")
                || lower.contains("copyright") || lower.contains("myhomelib:keep");
    }

    private boolean needsSeparator(StringBuilder left, String source, int rightIndex) {
        if (left.isEmpty() || rightIndex >= source.length()) return false;
        char a = left.charAt(left.length() - 1), b = source.charAt(rightIndex);
        return tokenChar(a) && tokenChar(b);
    }

    private boolean tokenChar(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c >= 0x80; }

    private boolean isTextCandidate(String path, EpubResourceNode node) {
        String media = node == null || node.mediaType() == null ? "" : node.mediaType().toLowerCase(Locale.ROOT);
        String lower = path.toLowerCase(Locale.ROOT);
        return isCss(path, node) || media.contains("xhtml") || media.equals("text/html") || media.contains("xml")
                || media.contains("svg") || media.contains("smil")
                || lower.matches(".*\\.(xhtml|html|htm|xml|opf|ncx|svg|smil)$");
    }

    private boolean isCss(String path, EpubResourceNode node) {
        String media = node == null || node.mediaType() == null ? "" : node.mediaType().toLowerCase(Locale.ROOT);
        return media.equals("text/css") || path.toLowerCase(Locale.ROOT).endsWith(".css");
    }

    private boolean hasScripting(EpubResourceGraph graph) {
        return graph.nodes().values().stream().anyMatch(node -> {
            String media = node.mediaType() == null ? "" : node.mediaType().toLowerCase(Locale.ROOT);
            return node.properties().contains("scripted") || media.contains("javascript") || media.contains("ecmascript");
        });
    }

    private void writeMimetype(ZipOutputStream out) throws Exception {
        CRC32 crc = new CRC32(); crc.update(MIMETYPE);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED); entry.setSize(MIMETYPE.length); entry.setCompressedSize(MIMETYPE.length);
        entry.setCrc(crc.getValue()); entry.setTime(ZIP_TIME.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()); entry.setExtra(null);
        out.putNextEntry(entry); out.write(MIMETYPE); out.closeEntry();
    }

    public record Result(int changedResources, long beforeBytes, long afterBytes) {
        public Result { changedResources = Math.max(0, changedResources); beforeBytes = Math.max(0L, beforeBytes); afterBytes = Math.max(0L, afterBytes); }
        static Result none() { return new Result(0, 0L, 0L); }
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("EPUB optimization cancelled");
    }

}
