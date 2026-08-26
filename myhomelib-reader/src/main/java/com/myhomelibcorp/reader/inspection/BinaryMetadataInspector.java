package com.myhomelibcorp.reader.inspection;

import com.myhomelibcorp.reader.api.BookSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BinaryMetadataInspector {

    private static final int MAX_PREFIX = 4 * 1024 * 1024;
    private static final int MAX_RECORD0 = 4 * 1024 * 1024;
    private static final int MAX_PDB_DIRECTORY = 2 * 1024 * 1024;

    private BinaryMetadataInspector() {
    }

    static DocumentInspection inspect(BookSource source) {
        String ext = source.extension().toLowerCase(Locale.ROOT);
        try {
            return switch (ext) {
                case "mobi", "azw", "azw3" -> inspectMobi(source);
                case "pdf" -> inspectPdf(source);
                case "djvu", "djv" -> inspectDjvu(source);
                default -> DocumentInspection.unsupported(ext.toUpperCase(Locale.ROOT),
                        "Формат не підтримує поглиблений аналіз");
            };
        } catch (Exception e) {
            return DocumentInspection.unsupported(ext.toUpperCase(Locale.ROOT),
                    "Не вдалося прочитати метадані: " + safeMessage(e));
        }
    }

    private static DocumentInspection inspectMobi(BookSource source) throws IOException {
        byte[] header = readPrefix(source, 78);
        if (header.length < 78) return DocumentInspection.unsupported("MOBI", "Пошкоджений MOBI/PalmDB header");
        int recordCount = u16(header, 76);
        if (recordCount <= 0 || recordCount > 200_000) {
            return DocumentInspection.unsupported("MOBI", "Некоректна таблиця MOBI records");
        }
        long directorySize = 78L + recordCount * 8L;
        if (directorySize > MAX_PDB_DIRECTORY) {
            return DocumentInspection.unsupported("MOBI", "Таблиця MOBI records перевищує безпечний ліміт");
        }
        byte[] directory = readPrefix(source, (int) directorySize);
        if (directory.length < directorySize) return DocumentInspection.unsupported("MOBI", "Неповна таблиця MOBI records");
        long first = u32(directory, 78);
        long second = recordCount > 1 ? u32(directory, 86) : source.size().orElse(first + MAX_RECORD0);
        long record0Length = Math.max(0, second - first);
        if (record0Length <= 32 || record0Length > MAX_RECORD0) {
            record0Length = Math.min(MAX_RECORD0, Math.max(0, source.size().orElse(first + MAX_RECORD0) - first));
        }
        byte[] record0 = readRange(source, first, (int) record0Length);
        if (record0.length < 24 || !asciiEquals(record0, 16, "MOBI")) {
            return DocumentInspection.unsupported("MOBI", "PalmDB не містить MOBI header");
        }

        int mobiStart = 16;
        int headerLength = (int) u32(record0, mobiStart + 4);
        int encodingCode = (int) u32(record0, mobiStart + 12);
        Charset charset = encodingCode == 65001 ? StandardCharsets.UTF_8 : safeCharset("windows-1252");
        int fullNameOffset = safeInt(u32(record0, mobiStart + 68));
        int fullNameLength = safeInt(u32(record0, mobiStart + 72));
        String fullName = sliceString(record0, fullNameOffset, fullNameLength, charset);

        Map<Integer, List<byte[]>> exth = new LinkedHashMap<>();
        if (headerLength >= 116 && mobiStart + headerLength + 12 <= record0.length) {
            long flags = u32(record0, mobiStart + 112);
            if ((flags & 0x40) != 0) {
                int exthStart = mobiStart + headerLength;
                if (asciiEquals(record0, exthStart, "EXTH")) {
                    int exthLength = safeInt(u32(record0, exthStart + 4));
                    int count = safeInt(u32(record0, exthStart + 8));
                    int end = Math.min(record0.length, exthStart + Math.max(12, exthLength));
                    int pos = exthStart + 12;
                    for (int i = 0; i < count && pos + 8 <= end && i < 10_000; i++) {
                        int type = safeInt(u32(record0, pos));
                        int len = safeInt(u32(record0, pos + 4));
                        if (len < 8 || pos + len > end) break;
                        byte[] data = java.util.Arrays.copyOfRange(record0, pos + 8, pos + len);
                        exth.computeIfAbsent(type, ignored -> new ArrayList<>()).add(data);
                        pos += len;
                    }
                }
            }
        }

        String title = firstText(exth, 503, charset);
        if (title.isBlank()) title = fullName;
        if (title.isBlank()) title = source.name();
        List<String> authors = allText(exth, 100, charset);
        String publisher = firstText(exth, 101, charset);
        String annotation = stripHtml(firstText(exth, 103, charset));
        String year = firstText(exth, 106, charset);
        String isbn = firstText(exth, 104, charset);
        String language = firstText(exth, 524, charset);

        return new DocumentInspection(true, "MOBI", title, authors, language, "", publisher, year, isbn,
                annotation, 0, 0, 0, List.of(), List.of(), "");
    }

    private static DocumentInspection inspectPdf(BookSource source) throws IOException {
        byte[] prefix = readPrefix(source, MAX_PREFIX);
        if (prefix.length < 5 || !(prefix[0] == '%' && prefix[1] == 'P' && prefix[2] == 'D' && prefix[3] == 'F')) {
            return DocumentInspection.unsupported("PDF", "Некоректний PDF header");
        }
        String latin = new String(prefix, StandardCharsets.ISO_8859_1);
        String title = pdfField(latin, "Title");
        String author = pdfField(latin, "Author");
        String subject = pdfField(latin, "Subject");
        String producer = pdfField(latin, "Producer");
        if (title.isBlank()) title = source.name();
        return new DocumentInspection(true, "PDF", title,
                author.isBlank() ? List.of() : List.of(author), "", "", producer, "", "", subject,
                0, 0, 0, List.of(), List.of(), "");
    }

    private static DocumentInspection inspectDjvu(BookSource source) throws IOException {
        byte[] prefix = readPrefix(source, Math.min(MAX_PREFIX, 1024 * 1024));
        boolean signature = prefix.length >= 16 && prefix[0] == 'A' && prefix[1] == 'T' && prefix[2] == '&' && prefix[3] == 'T';
        if (!signature) return DocumentInspection.unsupported("DJVU", "Некоректний DjVu header");
        return new DocumentInspection(true, "DJVU", source.name(), List.of(), "", "", "", "", "", "",
                0, 0, 0, List.of(), List.of(), "DjVu: доступні базові метадані; декодування сторінок не виконується");
    }

    private static String pdfField(String text, String key) {
        Pattern literal = Pattern.compile("/" + Pattern.quote(key) + "\\s*\\((.*?)\\)", Pattern.DOTALL);
        Matcher m = literal.matcher(text);
        if (m.find()) return decodePdfLiteral(m.group(1));
        Pattern hex = Pattern.compile("/" + Pattern.quote(key) + "\\s*<([0-9A-Fa-f]{4,})>");
        m = hex.matcher(text);
        if (m.find()) return decodePdfHex(m.group(1));
        return "";
    }

    private static String decodePdfLiteral(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean esc = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (esc) {
                out.append(switch (c) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> c; });
                esc = false;
            } else if (c == '\\') esc = true;
            else out.append(c);
        }
        return out.toString().trim();
    }

    private static String decodePdfHex(String hex) {
        try {
            int len = hex.length() / 2;
            byte[] bytes = new byte[len];
            for (int i = 0; i < len; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE).trim();
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstText(Map<Integer, List<byte[]>> exth, int type, Charset charset) {
        List<byte[]> values = exth.get(type);
        if (values == null || values.isEmpty()) return "";
        return cleanText(new String(values.getFirst(), charset));
    }

    private static List<String> allText(Map<Integer, List<byte[]>> exth, int type, Charset charset) {
        List<byte[]> values = exth.get(type);
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(bytes -> cleanText(new String(bytes, charset))).filter(v -> !v.isBlank()).distinct().toList();
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        return value.replace("\u0000", "").trim();
    }

    private static String stripHtml(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s+", "\n")
                .trim();
    }

    private static byte[] readPrefix(BookSource source, int max) throws IOException {
        try (InputStream in = source.openStream()) {
            return readBounded(in, max);
        }
    }

    private static byte[] readRange(BookSource source, long offset, int max) throws IOException {
        try (InputStream in = source.openStream()) {
            skipFully(in, offset);
            return readBounded(in, max);
        }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 64 * 1024));
        byte[] buffer = new byte[32 * 1024];
        int remaining = max;
        while (remaining > 0) {
            int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (n < 0) break;
            if (n == 0) continue;
            out.write(buffer, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = Math.max(0, bytes);
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) remaining -= skipped;
            else if (in.read() >= 0) remaining--;
            else throw new IOException("Unexpected EOF while seeking");
        }
    }

    private static int u16(byte[] data, int pos) {
        if (pos < 0 || pos + 2 > data.length) return 0;
        return ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
    }

    private static long u32(byte[] data, int pos) {
        if (pos < 0 || pos + 4 > data.length) return 0;
        return ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).getInt() & 0xffffffffL;
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    private static boolean asciiEquals(byte[] data, int pos, String text) {
        if (pos < 0 || pos + text.length() > data.length) return false;
        for (int i = 0; i < text.length(); i++) if ((byte) text.charAt(i) != data[pos + i]) return false;
        return true;
    }

    private static String sliceString(byte[] data, int offset, int length, Charset charset) {
        if (offset < 0 || length <= 0 || offset >= data.length) return "";
        int end = Math.min(data.length, offset + length);
        return cleanText(new String(data, offset, end - offset, charset));
    }

    private static Charset safeCharset(String name) {
        try { return Charset.forName(name); } catch (Exception e) { return StandardCharsets.ISO_8859_1; }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
