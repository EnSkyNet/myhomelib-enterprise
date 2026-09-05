package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.ZipCharsetSupport;
import com.myhomelibcorp.infrastructure.util.LimitedInputStream;
import com.myhomelibcorp.shared.util.Utf8Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Streaming reader for MyHomeLib INP/INPX indexes.
 * ОПТИМІЗОВАНО: покращено швидкість читання та зменшено використання пам'яті
 */
@Component
@Slf4j
public class InpxReader {
    private static final char FIELD_DELIMITER = 0x04;
    private static final long MAX_METADATA_BYTES = 16L * 1024L * 1024L;
    private static final List<String> DEFAULT_STRUCTURE = List.of(
            "AUTHOR", "GENRE", "TITLE", "SERIES", "SERNO", "FILE", "SIZE",
            "LIBID", "DEL", "EXT", "DATE", "LANG", "KEYWORDS"
    );
    // Common LibRusEc/Flibusta variant used by real online catalogs that omit structure.info.
    // The only difference from the classic 13-field layout is LIBRATE before KEYWORDS.
    private static final List<String> DEFAULT_STRUCTURE_WITH_LIBRATE = List.of(
            "AUTHOR", "GENRE", "TITLE", "SERIES", "SERNO", "FILE", "SIZE",
            "LIBID", "DEL", "EXT", "DATE", "LANG", "LIBRATE", "KEYWORDS"
    );
    private static final List<String> ARCHIVE_EXTENSIONS = List.of(
            ".tar.bz2", ".tar.gz", ".tar.xz", ".fb2.zip", ".fb2zip",
            ".tbz2", ".tgz", ".txz", ".zip", ".7z", ".rar", ".cbr", ".cbz", ".cpio", ".jar", ".tar");

    public Iterator<InpxRecord> read(Path file) {
        return read(file, false);
    }

    /**
     * MyHomeLib compatibility: extra.inp is an online-only member. A standalone .inp explicitly
     * selected by the user remains readable; the policy applies only to members of an INPX archive.
     */
    public Iterator<InpxRecord> read(Path file, boolean onlineCollection) {
        Objects.requireNonNull(file, "file");
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".inp")) {
            return readStandaloneInp(file);
        }
        if (!lower.endsWith(".inpx")) {
            throw new IllegalArgumentException("Unsupported INPX source: " + file);
        }
        try {
            return new ZipInpxIterator(file, onlineCollection);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read INPX: " + file, e);
        }
    }

    public long count(Path file) {
        return count(file, null, false);
    }

    /**
     * Counts source records in a streaming pass. Returns -1 when cancelled.
     * ОПТИМІЗОВАНО: швидший підрахунок без створення об'єктів
     */
    public long count(Path file, java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        return count(file, cancelFlag, false);
    }

    public long count(Path file, java.util.concurrent.atomic.AtomicBoolean cancelFlag, boolean onlineCollection) {
        long count = 0;
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".inp")) {
            try (InputStream input = Files.newInputStream(file)) {
                return countLines(input, cancelFlag);
            } catch (IOException e) {
                throw new UncheckedIOException("Не вдалося прочитати INP: " + file, e);
            }
        }

        if (!lower.endsWith(".inpx")) {
            throw new IllegalArgumentException("Unsupported INPX source: " + file);
        }

        try (ZipFile zip = openZip(file)) {
            validateArchive(zip);
            List<? extends ZipEntry> inpEntries = zip.stream()
                    .filter(e -> isCatalogInpMember(e, onlineCollection))
                    .sorted(Comparator.comparing(ZipEntry::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (inpEntries.isEmpty()) {
                throw new IOException("No .inp entries in " + file);
            }
            for (ZipEntry entry : inpEntries) {
                try (InputStream input = boundedEntryStream(zip, entry, ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
                    long entryCount = countLines(input, cancelFlag);
                    if (entryCount < 0) return -1L;
                    count += entryCount;
                }
            }
            log.debug("Підраховано {} записів INPX без декодування рядків", count);
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати INPX: " + file, e);
        }
    }

    private static boolean isCatalogInpMember(ZipEntry entry, boolean onlineCollection) {
        if (entry == null || entry.isDirectory()) return false;
        String normalized = entry.getName().replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".inp")) return false;
        int slash = lower.lastIndexOf('/');
        String fileName = slash >= 0 ? lower.substring(slash + 1) : lower;
        return onlineCollection || !fileName.equals("extra.inp");
    }

    static void closeIterator(Iterator<?> iterator) {
        if (iterator instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Cannot close INPX iterator", e);
            }
        }
    }

    private Iterator<InpxRecord> readStandaloneInp(Path file) {
        try {
            BufferedReader br = newDetectedReader(Files.newInputStream(file));
            String base = stripExtension(file.getFileName().toString());
            return new SingleReaderIterator(br, DEFAULT_STRUCTURE, true, file.getFileName().toString(), base + ".zip");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read INP: " + file, e);
        }
    }

    private static final class ZipInpxIterator implements Iterator<InpxRecord>, AutoCloseable {
        private final ZipFile zip;
        private final List<ZipEntry> inpEntries;
        private final List<String> structure;
        private final boolean inferFallbackStructure;
        private final Map<String, String> archivesByStem;
        private int entryIndex;
        private BufferedReader currentReader;
        private ZipEntry currentEntry;
        private InpxRecord next;
        private boolean closed;
        private long recordCount;

        private ZipInpxIterator(Path path, boolean onlineCollection) throws IOException {
            this.zip = openZip(path);
            validateArchive(this.zip);
            StructureInfo structureInfo = readStructure(zip);
            this.structure = structureInfo.fields();
            this.inferFallbackStructure = structureInfo.inferred();
            this.archivesByStem = readArchives(zip);
            this.inpEntries = zip.stream()
                    .filter(e -> isCatalogInpMember(e, onlineCollection))
                    .map(e -> (ZipEntry) e)
                    .sorted(Comparator.comparing(ZipEntry::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (inpEntries.isEmpty()) {
                close();
                throw new IOException("No .inp entries in " + path);
            }
            log.info("Знайдено {} INP файлів в INPX", inpEntries.size());
            advance();
        }

        @Override public boolean hasNext() { return next != null; }

        @Override public InpxRecord next() {
            if (next == null) throw new NoSuchElementException();
            InpxRecord result = next;
            advance();
            return result;
        }

        private void advance() {
            next = null;
            try {
                while (!closed) {
                    if (currentReader == null) {
                        if (entryIndex >= inpEntries.size()) {
                            log.info("Прочитано {} записів з INPX", recordCount);
                            close();
                            return;
                        }
                        currentEntry = inpEntries.get(entryIndex++);
                        currentReader = newDetectedReader(boundedEntryStream(zip, currentEntry, ArchiveSafetyLimits.MAX_ENTRY_BYTES));
                        log.debug("Читання INP: {}", currentEntry.getName());
                    }
                    String line = currentReader.readLine();
                    if (line == null) {
                        currentReader.close();
                        currentReader = null;
                        currentEntry = null;
                        continue;
                    }
                    if (line.isBlank()) continue;
                    String inpName = currentEntry.getName();
                    String archive = resolveArchiveName(inpName, archivesByStem);
                    next = parseLine(line, structure, inferFallbackStructure, inpName, archive);
                    recordCount++;
                    if (recordCount % 100_000 == 0 && recordCount > 0) {
                        log.info("Прочитано {} записів з INPX", recordCount);
                    }
                    return;
                }
            } catch (IOException e) {
                closeQuietly();
                throw new UncheckedIOException(e);
            }
        }

        private void closeQuietly() {
            try { close(); } catch (IOException ignored) { }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException error = null;
            if (currentReader != null) {
                try { currentReader.close(); } catch (IOException e) { error = e; }
            }
            try { zip.close(); } catch (IOException e) { if (error == null) error = e; }
            if (error != null) throw error;
        }
    }

    private static final class SingleReaderIterator implements Iterator<InpxRecord>, AutoCloseable {
        private final BufferedReader reader;
        private final List<String> structure;
        private final boolean inferFallbackStructure;
        private final String inpName;
        private final String archiveName;
        private String nextLine;
        private boolean closed;
        private long recordCount;

        private SingleReaderIterator(BufferedReader reader, List<String> structure, boolean inferFallbackStructure, String inpName, String archiveName) {
            this.reader = reader;
            this.structure = structure;
            this.inferFallbackStructure = inferFallbackStructure;
            this.inpName = inpName;
            this.archiveName = archiveName;
            advance();
        }

        @Override public boolean hasNext() { return nextLine != null; }
        @Override public InpxRecord next() {
            if (nextLine == null) throw new NoSuchElementException();
            String line = nextLine;
            advance();
            recordCount++;
            if (recordCount % 100_000 == 0 && recordCount > 0) {
                log.info("Прочитано {} записів з INP", recordCount);
            }
            return parseLine(line, structure, inferFallbackStructure, inpName, archiveName);
        }
        private void advance() {
            try {
                nextLine = reader.readLine();
                if (nextLine == null) close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            nextLine = null;
            reader.close();
        }
    }

    private static InpxRecord parseLine(String line, List<String> structure, boolean inferFallbackStructure, String inpName, String archiveName) {
        char delimiter = line.indexOf(FIELD_DELIMITER) >= 0 ? FIELD_DELIMITER : '|';
        if (inferFallbackStructure) {
            return parseFallbackLine(line, delimiter, inpName, archiveName);
        }
        Map<String, String> fields = InpxRecord.newParsedFields(structure.size());
        int fieldIndex = 0;
        int start = 0;
        for (int i = 0; i <= line.length() && fieldIndex < structure.size(); i++) {
            if (i == line.length() || line.charAt(i) == delimiter) {
                fields.put(structure.get(fieldIndex++), line.substring(start, i).trim());
                start = i + 1;
            }
        }
        while (fieldIndex < structure.size()) fields.put(structure.get(fieldIndex++), "");
        return InpxRecord.parsed(fields, inpName, archiveName);
    }

    /**
     * Parses a structure-less INP in one pass. The physical field count selects between the
     * classic 13-field layout and the common 14-field LibRusEc/Flibusta layout with LIBRATE.
     * A trailing delimiter terminates the final value and does not create an additional field.
     */
    private static InpxRecord parseFallbackLine(String line, char delimiter, String inpName, String archiveName) {
        String[] values = new String[DEFAULT_STRUCTURE_WITH_LIBRATE.size()];
        int fieldCount = 0;
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != delimiter) continue;
            if (fieldCount < values.length) values[fieldCount] = line.substring(start, i).trim();
            fieldCount++;
            start = i + 1;
        }
        if (start < line.length()) {
            if (fieldCount < values.length) values[fieldCount] = line.substring(start).trim();
            fieldCount++;
        }

        List<String> effectiveStructure = fieldCount == DEFAULT_STRUCTURE_WITH_LIBRATE.size()
                ? DEFAULT_STRUCTURE_WITH_LIBRATE
                : DEFAULT_STRUCTURE;
        Map<String, String> fields = InpxRecord.newParsedFields(effectiveStructure.size());
        for (int i = 0; i < effectiveStructure.size(); i++) {
            fields.put(effectiveStructure.get(i), i < values.length && values[i] != null ? values[i] : "");
        }
        return InpxRecord.parsed(fields, inpName, archiveName);
    }

    private static StructureInfo readStructure(ZipFile zip) throws IOException {
        ZipEntry entry = findIgnoreCase(zip, "structure.info");
        if (entry == null) return new StructureInfo(DEFAULT_STRUCTURE, true);
        try (BufferedReader reader = newDetectedReader(boundedEntryStream(zip, entry, MAX_METADATA_BYTES))) {
            String text = readSmallText(reader, 64 * 1024).replace("\uFEFF", "").trim();
            if (text.isBlank()) return new StructureInfo(DEFAULT_STRUCTURE, true);
            String firstLine = text.lines().filter(s -> !s.isBlank()).findFirst().orElse(text);
            String[] names = firstLine.indexOf(FIELD_DELIMITER) >= 0
                    ? firstLine.split(String.valueOf(FIELD_DELIMITER), -1)
                    : firstLine.split("[;|,]", -1);
            List<String> result = Arrays.stream(names)
                    .map(String::trim).map(s -> s.toUpperCase(Locale.ROOT))
                    .filter(s -> !s.isBlank()).toList();
            return result.isEmpty()
                    ? new StructureInfo(DEFAULT_STRUCTURE, true)
                    : new StructureInfo(result, false);
        }
    }

    private record StructureInfo(List<String> fields, boolean inferred) {
        private StructureInfo { fields = List.copyOf(fields); }
    }

    private static Map<String, String> readArchives(ZipFile zip) throws IOException {
        Map<String, String> result = new HashMap<>();
        ZipEntry entry = findIgnoreCase(zip, "archives.info");
        if (entry == null) return result;
        try (BufferedReader br = newDetectedReader(boundedEntryStream(zip, entry, MAX_METADATA_BYTES))) {
            for (String line; (line = br.readLine()) != null;) {
                ArchiveMapping mapping = parseArchiveMapping(line);
                if (mapping == null) continue;
                result.putIfAbsent(mapping.inpStem(), mapping.archiveName());
            }
        }
        return result;
    }

    private static ArchiveMapping parseArchiveMapping(String line) {
        if (line == null) return null;
        String cleaned = line.replace("\uFEFF", "").trim();
        if (cleaned.isEmpty()) return null;

        String[] tokens = cleaned.split("[\u0004;|\t]", -1);
        String first = null;
        String archive = null;
        for (String token : tokens) {
            String value = token.trim().replace('\\', '/');
            if (value.isEmpty()) continue;
            if (first == null) first = value;
            if (isArchiveName(value)) {
                archive = value;
                break;
            }
        }
        if (archive == null) return null;

        String key;
        if (first == null || first.equals(archive)) {
            key = stem(archive);
        } else {
            String firstFile = archiveFileName(first);
            key = stripExtension(firstFile).toLowerCase(Locale.ROOT);
        }
        return new ArchiveMapping(key, archive);
    }

    private static boolean isArchiveName(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String ext : ARCHIVE_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private record ArchiveMapping(String inpStem, String archiveName) { }

    private static String resolveArchiveName(String inpName, Map<String, String> archivesByStem) {
        String base = stripExtension(archiveFileName(inpName));
        return archivesByStem.getOrDefault(base.toLowerCase(Locale.ROOT), base + ".zip");
    }

    private static String stem(String name) {
        String file = archiveFileName(name);
        String lower = file.toLowerCase(Locale.ROOT);
        for (String ext : ARCHIVE_EXTENSIONS) {
            if (lower.endsWith(ext) && file.length() > ext.length()) {
                return file.substring(0, file.length() - ext.length()).toLowerCase(Locale.ROOT);
            }
        }
        return stripExtension(file).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String archiveFileName(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static ZipEntry findIgnoreCase(ZipFile zip, String wanted) {
        return zip.stream().filter(e -> archiveFileName(e.getName()).equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    /**
     * Counts logical text lines without decoding every record into a String. INPX files are frequently
     * 500k-1M+ rows, so this removes an otherwise throw-away allocation pass before the real import.
     */
    private static long countLines(InputStream input, java.util.concurrent.atomic.AtomicBoolean cancelFlag) throws IOException {
        final int sampleLimit = 16 * 1024;
        try (BufferedInputStream in = new BufferedInputStream(input, 256 * 1024)) {
            byte[] sample = in.readNBytes(sampleLimit);
            DetectedEncoding detected = detectEncoding(sample);
            FastLineCounter counter = new FastLineCounter(detected.charset());
            int skip = Math.min(detected.bomBytes(), sample.length);
            counter.accept(sample, skip, sample.length - skip);
            byte[] buffer = new byte[256 * 1024];
            for (int n; (n = in.read(buffer)) >= 0;) {
                if (cancelFlag != null && cancelFlag.get()) return -1L;
                if (n > 0) counter.accept(buffer, 0, n);
            }
            if (cancelFlag != null && cancelFlag.get()) return -1L;
            return counter.finish();
        }
    }

    private static final class FastLineCounter {
        private final boolean utf16le;
        private final boolean utf16be;
        private long breaks;
        private boolean hasContent;
        private boolean lineTerminated;
        private boolean pendingCr;
        private int pendingByte = -1;

        private FastLineCounter(Charset charset) {
            String name = charset.name().toUpperCase(Locale.ROOT);
            this.utf16le = name.equals("UTF-16LE");
            this.utf16be = name.equals("UTF-16BE");
        }

        private void accept(byte[] bytes, int offset, int length) {
            if (!utf16le && !utf16be) {
                for (int i = offset, end = offset + length; i < end; i++) {
                    acceptCharacter(bytes[i] & 0xff);
                }
                return;
            }
            int i = offset;
            int end = offset + length;
            if (pendingByte >= 0 && i < end) {
                acceptCodeUnit(pendingByte, bytes[i++] & 0xff);
                pendingByte = -1;
            }
            while (i + 1 < end) acceptCodeUnit(bytes[i++] & 0xff, bytes[i++] & 0xff);
            if (i < end) pendingByte = bytes[i] & 0xff;
        }

        private void acceptCodeUnit(int first, int second) {
            int codeUnit = utf16le ? (first | (second << 8)) : ((first << 8) | second);
            acceptCharacter(codeUnit);
        }

        private void acceptCharacter(int value) {
            hasContent = true;
            if (pendingCr) {
                if (value == 0x0A) {
                    breaks++;
                    pendingCr = false;
                    lineTerminated = true;
                    return;
                }
                breaks++;
                pendingCr = false;
                lineTerminated = true;
            }
            if (value == 0x0D) {
                pendingCr = true;
                lineTerminated = false;
            } else if (value == 0x0A) {
                breaks++;
                lineTerminated = true;
            } else {
                lineTerminated = false;
            }
        }

        private long finish() {
            // A trailing odd UTF-16 byte is malformed input, but BufferedReader would still expose
            // the preceding logical line. Leave semantic validation to the real decoding pass.
            if (pendingByte >= 0) {
                hasContent = true;
                lineTerminated = false;
            }
            if (pendingCr) {
                breaks++;
                pendingCr = false;
                lineTerminated = true;
            }
            return breaks + (hasContent && !lineTerminated ? 1 : 0);
        }
    }

    private static BufferedReader newDetectedReader(InputStream input) throws IOException {
        final int sampleLimit = 16 * 1024;
        PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(input, 64 * 1024), sampleLimit);
        byte[] sample = in.readNBytes(sampleLimit);
        DetectedEncoding detected = detectEncoding(sample);
        int skip = Math.min(detected.bomBytes(), sample.length);
        if (sample.length > skip) in.unread(sample, skip, sample.length - skip);
        return new BufferedReader(new InputStreamReader(in, detected.charset()), 64 * 1024);
    }

    private static DetectedEncoding detectEncoding(byte[] sample) {
        if (sample.length >= 3 && (sample[0] & 0xff) == 0xef && (sample[1] & 0xff) == 0xbb && (sample[2] & 0xff) == 0xbf)
            return new DetectedEncoding(StandardCharsets.UTF_8, 3);
        if (sample.length >= 2 && (sample[0] & 0xff) == 0xff && (sample[1] & 0xff) == 0xfe)
            return new DetectedEncoding(StandardCharsets.UTF_16LE, 2);
        if (sample.length >= 2 && (sample[0] & 0xff) == 0xfe && (sample[1] & 0xff) == 0xff)
            return new DetectedEncoding(StandardCharsets.UTF_16BE, 2);
        if (Utf8Validator.isValid(sample)) return new DetectedEncoding(StandardCharsets.UTF_8, 0);

        Charset cp1251 = Charset.forName("windows-1251");
        Charset cp866 = Charset.forName("CP866");
        String a = new String(sample, cp1251);
        String b = new String(sample, cp866);
        return textScore(b) > textScore(a) + 4
                ? new DetectedEncoding(cp866, 0)
                : new DetectedEncoding(cp1251, 0);
    }


    private static int textScore(String text) {
        int score = 0;
        String common = "оеаинтсрвлкмдпуяыьгзбчйхжшюцщэфъёіїєґ";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char lower = Character.toLowerCase(c);
            if (Character.isLetterOrDigit(c)) score += 1;
            if ((c >= 'А' && c <= 'я') || "ІіЇїЄєҐґЁё".indexOf(c) >= 0) score += 2;
            if (common.indexOf(lower) >= 0) score += 2;
            if (Character.isWhitespace(c) || c == 0x04 || ",.;:!?-'\"/()[]".indexOf(c) >= 0) score += 1;
            if (Character.isISOControl(c) && !Character.isWhitespace(c) && c != 0x04) score -= 6;
            if (c >= 0x2500 && c <= 0x259f) score -= 4;
            if (c == '\ufffd') score -= 20;
        }
        return score;
    }

    private static String readSmallText(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder text = new StringBuilder(Math.min(4096, maxChars));
        char[] buffer = new char[4096];
        while (text.length() < maxChars) {
            int n = reader.read(buffer, 0, Math.min(buffer.length, maxChars - text.length()));
            if (n < 0) break;
            text.append(buffer, 0, n);
        }
        return text.toString();
    }

    private record DetectedEncoding(Charset charset, int bomBytes) { }


    private static void validateArchive(ZipFile zip) throws IOException {
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                throw new IOException("INPX contains too many entries: " + count);
            }
            if (entry.isDirectory()) continue;
            if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
                throw new IOException("INPX entry is too large: " + entry.getName() + " (" + entry.getSize() + " bytes)");
            }
            long compressed = entry.getCompressedSize();
            long size = entry.getSize();
            if (compressed > 0 && size > 0
                    && size / Math.max(1L, compressed) > ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) {
                throw new IOException("INPX entry has suspicious compression ratio: " + entry.getName());
            }
        }
    }

    private static InputStream boundedEntryStream(ZipFile zip, ZipEntry entry, long maxBytes) throws IOException {
        if (entry == null) throw new IOException("Missing INPX entry");
        if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize()) || entry.getSize() > maxBytes) {
            throw new IOException("INPX entry is too large: " + entry.getName());
        }
        return new LimitedInputStream(zip.getInputStream(entry), maxBytes);
    }

    private static ZipFile openZip(Path path) throws IOException {
        return ZipCharsetSupport.open(path);
    }
}