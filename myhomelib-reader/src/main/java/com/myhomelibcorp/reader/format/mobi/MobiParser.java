package com.myhomelibcorp.reader.format.mobi;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.resource.HybridResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
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

/**
 * Safe built-in parser for unencrypted MOBI/AZW/AZW3 PalmDB containers.
 *
 * <p>It supports the two common PalmDOC text encodings used by reflowable MOBI/KF8 books:
 * uncompressed records and PalmDOC compression. DRM-protected books are rejected explicitly.
 * HUFF/CDIC-compressed books are detected and rejected with a precise message instead of
 * rendering binary garbage. The Reader can therefore open ordinary DRM-free MOBI/AZW files
 * natively and a substantial subset of AZW3 files without an external converter.</p>
 */
public final class MobiParser implements BookParser {
    private static final int PDB_HEADER_SIZE = 78;
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 384 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 300_000_000;
    private static final int MAX_RECORDS = 65_535;
    private static final Pattern ENTITY = Pattern.compile("&(#(?:x[0-9a-fA-F]+|[0-9]+)|[A-Za-z][A-Za-z0-9]+);");
    private static final Pattern HEADING_MARKER = Pattern.compile("^@@H([1-6])@@(.*)$");

    @Override
    public BookDocumentMetadata readMetadata(BookSource source) throws IOException {
        Container container = Container.read(source);
        BookMetadata metadata = metadata(source, container);
        return new BookDocumentMetadataSnapshot(metadata, container.textLength, false, 1);
    }

    @Override
    public ReaderDocument parse(BookSource source, ParseOptions options) throws IOException {
        if (source == null) throw new IOException("MOBI source is null");
        ParseOptions effective = options == null ? ParseOptions.defaultOptions() : options;
        Container container = Container.read(source);
        if (container.encryption != 0) {
            throw new IOException("MOBI/AZW захищено DRM (encryption=" + container.encryption + "). "
                    + "Вбудований Reader відкриває лише незашифровані книги.");
        }
        if (container.compression == 17480) {
            throw new IOException("Цей MOBI/AZW3 використовує HUFF/CDIC compression. "
                    + "Для цього різновиду потрібна попередня конвертація в EPUB/FB2.");
        }
        if (container.compression != 1 && container.compression != 2) {
            throw new IOException("Непідтримуваний тип MOBI compression: " + container.compression);
        }

        byte[] textBytes = extractText(container);
        Charset charset = effective.preferredEncoding() == null || effective.preferredEncoding().isBlank()
                ? container.charset
                : safeCharset(effective.preferredEncoding(), container.charset);
        String html = new String(textBytes, charset).replace("\u0000", "");
        ParsedText parsed = parseMarkup(html, fallbackTitle(source.name()), effective.buildToc());
        if (parsed.text.length() == 0) throw new IOException("MOBI/AZW не містить читабельного тексту");

        BookMetadata base = metadata(source, container);
        String title = meaningful(base.title()) ? base.title() : parsed.fallbackTitle;
        BookMetadata metadata = new BookMetadata(
                base.id(), title, base.authors(), base.language(), base.series(), base.sequenceNumber(),
                base.genres(), base.annotation(), base.publisher(), base.year(), base.isbn(), base.fileSize());

        List<ChapterIndex> chapters = buildChapters(parsed, title);
        return CompactReaderDocument.builder()
                .metadata(metadata)
                .chapters(chapters)
                .resources(new HybridResourceRepository())
                .text(parsed.text)
                .toc(parsed.toc)
                .totalTextLength(parsed.text.length())
                .build();
    }

    private static byte[] extractText(Container container) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(container.textLength, 4 * 1024 * 1024L));
        int limit = Math.min(container.textRecordCount, container.recordOffsets.length - 1);
        for (int i = 1; i <= limit; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("MOBI parsing cancelled");
            int start = container.recordOffsets[i];
            int end = i + 1 < container.recordOffsets.length ? container.recordOffsets[i + 1] : container.bytes.length;
            if (start < 0 || end < start || end > container.bytes.length) throw new IOException("Пошкоджена MOBI record table");
            byte[] record = java.util.Arrays.copyOfRange(container.bytes, start, end);
            int contentLength = stripTrailingDataLength(record, container.extraDataFlags);
            if (contentLength < 0 || contentLength > record.length) throw new IOException("Некоректні MOBI trailing data");
            if (container.compression == 1) {
                appendBounded(out, record, 0, contentLength);
            } else {
                byte[] expanded = decompressPalmDoc(record, contentLength);
                appendBounded(out, expanded, 0, expanded.length);
            }
            if (container.textLength > 0 && out.size() >= container.textLength) break;
        }
        byte[] result = out.toByteArray();
        if (container.textLength > 0 && result.length > container.textLength) {
            return java.util.Arrays.copyOf(result, (int) Math.min(container.textLength, Integer.MAX_VALUE));
        }
        return result;
    }

    private static byte[] decompressPalmDoc(byte[] input, int length) throws IOException {
        PalmDocBuffer out = new PalmDocBuffer(Math.min(Math.max(64, length * 2), 256 * 1024));
        int i = 0;
        while (i < length) {
            int c = input[i++] & 0xff;
            if (c == 0) {
                out.write(0);
            } else if (c <= 8) {
                int count = Math.min(c, length - i);
                out.write(input, i, count);
                i += count;
            } else if (c <= 0x7f) {
                out.write(c);
            } else if (c <= 0xbf) {
                if (i >= length) throw new IOException("Обрізаний PalmDOC back-reference");
                int pair = (c << 8) | (input[i++] & 0xff);
                int distance = (pair >> 3) & 0x7ff;
                int count = (pair & 0x7) + 3;
                if (distance <= 0 || distance > out.size()) throw new IOException("Некоректний PalmDOC back-reference");
                for (int j = 0; j < count; j++) {
                    // Copy byte-by-byte so overlapping back-references work exactly like LZ77.
                    out.write(out.byteAt(out.size() - distance));
                }
            } else {
                out.write(' ');
                out.write(c ^ 0x80);
            }
        }
        return out.toByteArray();
    }

    private static int stripTrailingDataLength(byte[] record, int flags) {
        int size = record.length;
        int trailingFlags = flags >>> 1;
        while (trailingFlags != 0 && size > 0) {
            if ((trailingFlags & 1) != 0) {
                int entry = trailingEntrySize(record, size);
                if (entry <= 0 || entry > size) return size;
                size -= entry;
            }
            trailingFlags >>>= 1;
        }
        if ((flags & 1) != 0 && size > 0) {
            int multibyte = (record[size - 1] & 0x03) + 1;
            size = Math.max(0, size - multibyte);
        }
        return size;
    }

    private static int trailingEntrySize(byte[] record, int size) {
        int result = 0;
        int shift = 0;
        int pos = size - 1;
        while (pos >= 0 && shift < 28) {
            int value = record[pos--] & 0xff;
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) != 0) return result;
            shift += 7;
        }
        return 0;
    }

    private static ParsedText parseMarkup(String input, String fallbackTitle, boolean buildToc) throws IOException {
        String html = input == null ? "" : input;
        html = html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", " ");
        html = html.replaceAll("(?is)<h([1-6])\\b[^>]*>", "\n\n@@H$1@@");
        html = html.replaceAll("(?is)</h[1-6]\\s*>", "\n\n");
        html = html.replaceAll("(?is)<br\\s*/?>", "\n");
        html = html.replaceAll("(?is)<li\\b[^>]*>", "\n• ");
        html = html.replaceAll("(?is)</?(p|div|section|article|blockquote|pre|tr|ul|ol|table|hr)\\b[^>]*>", "\n");
        html = html.replaceAll("(?is)<[^>]+>", " ");
        html = decodeEntities(html);
        html = html.replace('\r', '\n').replaceAll("\\n{3,}", "\n\n");

        TextStorageImpl text = new TextStorageImpl();
        DefaultTableOfContents toc = new DefaultTableOfContents();
        List<Heading> headings = new ArrayList<>();
        String discoveredTitle = "";
        for (String rawLine : html.split("\\n")) {
            String line = rawLine.replaceAll("[\\t\\x0B\\f ]+", " ").strip();
            if (line.isEmpty()) continue;
            int level = 0;
            Matcher heading = HEADING_MARKER.matcher(line);
            if (heading.matches()) {
                level = Integer.parseInt(heading.group(1));
                line = heading.group(2).strip();
                if (line.isEmpty()) continue;
            }
            if ((long) text.length() + line.length() + 1 > MAX_TEXT_CHARS) {
                throw new IOException("MOBI text exceeds Reader safety limit of " + MAX_TEXT_CHARS + " characters");
            }
            int offset = text.length();
            TextStyle style = headingStyle(level);
            text.startParagraph(style);
            text.append(line, style);
            text.append("\n", TextStyle.NORMAL);
            if (level > 0) {
                if (discoveredTitle.isBlank()) discoveredTitle = line;
                headings.add(new Heading(line, offset, level));
                if (buildToc) toc.addEntry(line, offset, level);
            }
        }
        if (text.length() == 0) return new ParsedText(text, toc, List.of(), fallbackTitle);
        return new ParsedText(text, toc, List.copyOf(headings), discoveredTitle.isBlank() ? fallbackTitle : discoveredTitle);
    }

    private static List<ChapterIndex> buildChapters(ParsedText parsed, String title) {
        List<Heading> chapterHeadings = parsed.headings.stream().filter(h -> h.level <= 2).toList();
        if (chapterHeadings.isEmpty()) {
            return List.of(new ChapterIndex("mobi:0", title, 0, parsed.text.length(), parsed.text.getParagraphCount()));
        }
        List<ChapterIndex> chapters = new ArrayList<>();
        for (int i = 0; i < chapterHeadings.size(); i++) {
            Heading h = chapterHeadings.get(i);
            long end = i + 1 < chapterHeadings.size() ? chapterHeadings.get(i + 1).offset : parsed.text.length();
            chapters.add(new ChapterIndex("mobi:" + i, h.title, h.offset, end, 0));
        }
        return List.copyOf(chapters);
    }

    private static TextStyle headingStyle(int level) {
        return switch (level) {
            case 1 -> TextStyle.HEADING_1;
            case 2 -> TextStyle.HEADING_2;
            case 3 -> TextStyle.HEADING_3;
            case 4 -> TextStyle.HEADING_4;
            case 5 -> TextStyle.HEADING_5;
            case 6 -> TextStyle.HEADING_6;
            default -> TextStyle.NORMAL;
        };
    }

    private static String decodeEntities(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = switch (token.toLowerCase(Locale.ROOT)) {
                case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\"";
                case "apos" -> "'"; case "nbsp" -> " "; case "ndash" -> "–"; case "mdash" -> "—";
                case "hellip" -> "…"; case "laquo" -> "«"; case "raquo" -> "»";
                default -> numericEntity(token);
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String numericEntity(String token) {
        if (!token.startsWith("#")) return "&" + token + ";";
        try {
            int codePoint = token.startsWith("#x") || token.startsWith("#X")
                    ? Integer.parseInt(token.substring(2), 16)
                    : Integer.parseInt(token.substring(1));
            return Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : "�";
        } catch (RuntimeException ignored) {
            return "&" + token + ";";
        }
    }

    private static BookMetadata metadata(BookSource source, Container c) {
        String title = firstText(c.exth, 503, c.charset);
        if (!meaningful(title)) title = c.fullName;
        if (!meaningful(title)) title = fallbackTitle(source.name());
        List<String> authors = allText(c.exth, 100, c.charset);
        if (authors.isEmpty()) authors = List.of("Невідомий автор");
        String publisher = firstText(c.exth, 101, c.charset);
        String annotation = stripHtml(firstText(c.exth, 103, c.charset));
        String isbn = firstText(c.exth, 104, c.charset);
        String year = firstText(c.exth, 106, c.charset);
        String language = firstText(c.exth, 524, c.charset);
        OptionalLong size = source.size();
        return new BookMetadata(source.id(), title, authors, language, null, null, List.of(), annotation,
                publisher, year, isbn.isBlank() ? null : isbn, size.orElse(c.bytes.length));
    }

    private static String stripHtml(String value) {
        if (value == null || value.isBlank()) return "";
        return decodeEntities(value.replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)<[^>]+>", " "))
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s+", "\n").trim();
    }

    private static String firstText(Map<Integer, List<byte[]>> exth, int type, Charset charset) {
        List<byte[]> values = exth.get(type);
        return values == null || values.isEmpty() ? "" : clean(new String(values.getFirst(), charset));
    }

    private static List<String> allText(Map<Integer, List<byte[]>> exth, int type, Charset charset) {
        List<byte[]> values = exth.get(type);
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(v -> clean(new String(v, charset))).filter(MobiParser::meaningful).distinct().toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\u0000", "").trim();
    }

    private static boolean meaningful(String value) { return value != null && !value.isBlank(); }

    private static String fallbackTitle(String name) {
        if (name == null || name.isBlank()) return "Без назви";
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).strip();
    }

    private static Charset safeCharset(String name, Charset fallback) {
        try { return Charset.forName(name); } catch (RuntimeException ignored) { return fallback; }
    }

    private static void appendBounded(ByteArrayOutputStream out, byte[] bytes, int off, int len) throws IOException {
        if ((long) out.size() + len > MAX_TEXT_BYTES) throw new IOException("MOBI text exceeds Reader byte safety limit");
        out.write(bytes, off, len);
    }

    private record Heading(String title, int offset, int level) { }
    private record ParsedText(TextStorageImpl text, DefaultTableOfContents toc, List<Heading> headings, String fallbackTitle) { }

    /** Mutable bounded buffer used by PalmDOC LZ back-references without O(n²) copies. */
    private static final class PalmDocBuffer {
        private byte[] data;
        private int size;

        private PalmDocBuffer(int initialCapacity) {
            data = new byte[Math.max(64, initialCapacity)];
        }

        int size() { return size; }

        int byteAt(int index) throws IOException {
            if (index < 0 || index >= size) throw new IOException("Некоректний PalmDOC back-reference");
            return data[index] & 0xff;
        }

        void write(int value) throws IOException {
            ensureCapacity(1);
            data[size++] = (byte) value;
        }

        void write(byte[] source, int offset, int length) throws IOException {
            if (length <= 0) return;
            ensureCapacity(length);
            System.arraycopy(source, offset, data, size, length);
            size += length;
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(data, size);
        }

        private void ensureCapacity(int additional) throws IOException {
            long required = (long) size + additional;
            if (required > MAX_TEXT_BYTES) throw new IOException("MOBI text exceeds Reader byte safety limit");
            if (required <= data.length) return;
            int next = data.length;
            while (next < required) {
                next = Math.min(MAX_TEXT_BYTES, Math.max(next + 1, next << 1));
                if (next >= required) break;
            }
            data = java.util.Arrays.copyOf(data, next);
        }
    }

    private static final class Container {
        private final byte[] bytes;
        private final int[] recordOffsets;
        private final int compression;
        private final long textLength;
        private final int textRecordCount;
        private final int encryption;
        private final int extraDataFlags;
        private final Charset charset;
        private final String fullName;
        private final Map<Integer, List<byte[]>> exth;

        private Container(byte[] bytes, int[] recordOffsets, int compression, long textLength, int textRecordCount,
                          int encryption, int extraDataFlags, Charset charset, String fullName,
                          Map<Integer, List<byte[]>> exth) {
            this.bytes = bytes;
            this.recordOffsets = recordOffsets;
            this.compression = compression;
            this.textLength = textLength;
            this.textRecordCount = textRecordCount;
            this.encryption = encryption;
            this.extraDataFlags = extraDataFlags;
            this.charset = charset;
            this.fullName = fullName;
            this.exth = exth;
        }

        static Container read(BookSource source) throws IOException {
            if (source == null) throw new IOException("MOBI source is null");
            byte[] bytes;
            try (InputStream in = source.openStream()) {
                bytes = readBounded(in, MAX_FILE_BYTES);
            }
            if (bytes.length < PDB_HEADER_SIZE) throw new IOException("Пошкоджений MOBI/PalmDB header");
            int records = u16(bytes, 76);
            if (records <= 0 || records > MAX_RECORDS || PDB_HEADER_SIZE + records * 8L > bytes.length) {
                throw new IOException("Некоректна MOBI record table");
            }
            int[] offsets = new int[records];
            for (int i = 0; i < records; i++) {
                long value = u32(bytes, PDB_HEADER_SIZE + i * 8);
                if (value < 0 || value > bytes.length) throw new IOException("MOBI record offset outside file");
                offsets[i] = (int) value;
                if (i > 0 && offsets[i] < offsets[i - 1]) throw new IOException("MOBI record offsets are not ordered");
            }
            int record0Start = offsets[0];
            int record0End = records > 1 ? offsets[1] : bytes.length;
            if (record0Start < 0 || record0End <= record0Start || record0End > bytes.length || record0End - record0Start < 40) {
                throw new IOException("Пошкоджений MOBI record 0");
            }
            byte[] record0 = java.util.Arrays.copyOfRange(bytes, record0Start, record0End);
            int compression = u16(record0, 0);
            long textLength = u32(record0, 4);
            int textRecordCount = u16(record0, 8);
            int encryption = u16(record0, 12);
            if (!ascii(record0, 16, "MOBI")) throw new IOException("PalmDB не містить MOBI header");
            int mobiLength = safeInt(u32(record0, 20));
            if (mobiLength < 84 || 16L + mobiLength > record0.length) throw new IOException("Некоректний MOBI header length");
            long encodingCode = u32(record0, 28);
            Charset charset = encodingCode == 65001 ? StandardCharsets.UTF_8
                    : encodingCode == 65002 ? StandardCharsets.UTF_16BE
                    : safeCharset("windows-1252", StandardCharsets.ISO_8859_1);
            int nameOffset = safeInt(u32(record0, 84));
            int nameLength = safeInt(u32(record0, 88));
            String fullName = slice(record0, nameOffset, nameLength, charset);
            int extraFlags = mobiLength >= 244 && 16 + 244 <= record0.length ? u16(record0, 16 + 242) : 0;
            Map<Integer, List<byte[]>> exth = parseExth(record0, mobiLength);
            return new Container(bytes, offsets, compression, textLength, textRecordCount, encryption,
                    extraFlags, charset, fullName, exth);
        }

        private static Map<Integer, List<byte[]>> parseExth(byte[] record0, int mobiLength) {
            Map<Integer, List<byte[]>> result = new LinkedHashMap<>();
            if (mobiLength < 116 || 16 + 116 > record0.length) return result;
            long flags = u32(record0, 16 + 112);
            if ((flags & 0x40) == 0) return result;
            int start = 16 + mobiLength;
            if (start + 12 > record0.length || !ascii(record0, start, "EXTH")) return result;
            int length = safeInt(u32(record0, start + 4));
            int count = safeInt(u32(record0, start + 8));
            int end = Math.min(record0.length, start + Math.max(12, length));
            int pos = start + 12;
            for (int i = 0; i < count && i < 10_000 && pos + 8 <= end; i++) {
                int type = safeInt(u32(record0, pos));
                int len = safeInt(u32(record0, pos + 4));
                if (len < 8 || pos + len > end) break;
                byte[] data = java.util.Arrays.copyOfRange(record0, pos + 8, pos + len);
                result.computeIfAbsent(type, ignored -> new ArrayList<>()).add(data);
                pos += len;
            }
            return result;
        }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 1024 * 1024));
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("MOBI parsing cancelled");
            if ((long) out.size() + read > max) throw new IOException("MOBI exceeds Reader safety limit of " + max + " bytes");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static int u16(byte[] data, int offset) throws IOException {
        if (offset < 0 || offset + 2 > data.length) throw new IOException("Unexpected end of MOBI data");
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static long u32(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) return 0;
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }

    private static int safeInt(long value) { return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value); }

    private static boolean ascii(byte[] data, int offset, String value) {
        if (offset < 0 || offset + value.length() > data.length) return false;
        for (int i = 0; i < value.length(); i++) if ((char) (data[offset + i] & 0xff) != value.charAt(i)) return false;
        return true;
    }

    private static String slice(byte[] data, int offset, int length, Charset charset) {
        if (offset < 0 || length <= 0 || offset >= data.length) return "";
        int end = Math.min(data.length, offset + length);
        return clean(new String(data, offset, end - offset, charset));
    }
}
