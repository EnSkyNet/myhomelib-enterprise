package com.myhomelibcorp.infrastructure.importengine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Streaming reader for MyHomeLib INP/INPX indexes.
 * Supports multiple .inp entries, structure.info and archives.info.
 */
@Component
@Slf4j
public class InpxReader {
    private static final char FIELD_DELIMITER = 0x04;
    private static final List<String> DEFAULT_STRUCTURE = List.of(
            "AUTHOR", "GENRE", "TITLE", "SERIES", "SERNO", "FILE", "SIZE",
            "LIBID", "DEL", "EXT", "DATE", "LANG", "KEYWORDS"
    );
    private static final List<String> ARCHIVE_EXTENSIONS = List.of(
            ".tar.bz2", ".tar.gz", ".tar.xz", ".fb2.zip", ".fb2zip",
            ".tbz2", ".tgz", ".txz", ".zip", ".7z", ".rar", ".cbr", ".cbz", ".cpio", ".jar", ".tar");

    public Iterator<InpxRecord> read(Path file) {
        Objects.requireNonNull(file, "file");
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".inp")) {
            return readStandaloneInp(file);
        }
        if (!lower.endsWith(".inpx")) {
            throw new IllegalArgumentException("Unsupported INPX source: " + file);
        }
        try {
            return new ZipInpxIterator(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read INPX: " + file, e);
        }
    }

    public long count(Path file) {
        long count = 0;
        Iterator<InpxRecord> it = read(file);
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }

    private Iterator<InpxRecord> readStandaloneInp(Path file) {
        try {
            BufferedReader br = newDetectedReader(Files.newInputStream(file));
            String base = stripExtension(file.getFileName().toString());
            return new SingleReaderIterator(br, DEFAULT_STRUCTURE, file.getFileName().toString(), base + ".zip");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read INP: " + file, e);
        }
    }

    private static final class ZipInpxIterator implements Iterator<InpxRecord> {
        private final ZipFile zip;
        private final List<ZipEntry> inpEntries;
        private final List<String> structure;
        private final Map<String, String> archivesByStem;
        private int entryIndex;
        private BufferedReader currentReader;
        private ZipEntry currentEntry;
        private InpxRecord next;
        private boolean closed;

        private ZipInpxIterator(Path path) throws IOException {
            this.zip = openZip(path);
            this.structure = readStructure(zip);
            this.archivesByStem = readArchives(zip);
            this.inpEntries = zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".inp"))
                    .map(e -> (ZipEntry) e)
                    .sorted(Comparator.comparing(ZipEntry::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (inpEntries.isEmpty()) {
                close();
                throw new IOException("No .inp entries in " + path);
            }
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
                            close();
                            return;
                        }
                        currentEntry = inpEntries.get(entryIndex++);
                        currentReader = newDetectedReader(zip.getInputStream(currentEntry));
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
                    next = parseLine(line, structure, inpName, archive);
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

        private void close() throws IOException {
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

    private static final class SingleReaderIterator implements Iterator<InpxRecord> {
        private final BufferedReader reader;
        private final List<String> structure;
        private final String inpName;
        private final String archiveName;
        private String nextLine;
        private boolean closed;

        private SingleReaderIterator(BufferedReader reader, List<String> structure, String inpName, String archiveName) {
            this.reader = reader;
            this.structure = structure;
            this.inpName = inpName;
            this.archiveName = archiveName;
            advance();
        }

        @Override public boolean hasNext() { return nextLine != null; }
        @Override public InpxRecord next() {
            if (nextLine == null) throw new NoSuchElementException();
            String line = nextLine;
            advance();
            return parseLine(line, structure, inpName, archiveName);
        }
        private void advance() {
            try {
                nextLine = reader.readLine();
                if (nextLine == null && !closed) { closed = true; reader.close(); }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static InpxRecord parseLine(String line, List<String> structure, String inpName, String archiveName) {
        String[] values = split(line);
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < structure.size(); i++) {
            fields.put(structure.get(i), i < values.length ? values[i] : "");
        }
        return new InpxRecord(fields, inpName, archiveName);
    }

    private static String[] split(String line) {
        char delimiter = line.indexOf(FIELD_DELIMITER) >= 0 ? FIELD_DELIMITER : '|';
        List<String> result = new ArrayList<>(16);
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == delimiter) {
                result.add(line.substring(start, i));
                start = i + 1;
            }
        }
        result.add(line.substring(start));
        return result.toArray(String[]::new);
    }

    private static List<String> readStructure(ZipFile zip) {
        ZipEntry entry = findIgnoreCase(zip, "structure.info");
        if (entry == null) return DEFAULT_STRUCTURE;
        try (BufferedReader reader = newDetectedReader(zip.getInputStream(entry))) {
            String text = readSmallText(reader, 64 * 1024).replace("\uFEFF", "").trim();
            if (text.isBlank()) return DEFAULT_STRUCTURE;
            String firstLine = text.lines().filter(s -> !s.isBlank()).findFirst().orElse(text);
            String[] names = firstLine.indexOf(FIELD_DELIMITER) >= 0
                    ? firstLine.split(String.valueOf(FIELD_DELIMITER), -1)
                    : firstLine.split("[;|,]", -1);
            List<String> result = Arrays.stream(names)
                    .map(String::trim).map(s -> s.toUpperCase(Locale.ROOT))
                    .filter(s -> !s.isBlank()).toList();
            return result.isEmpty() ? DEFAULT_STRUCTURE : result;
        } catch (Exception e) {
            log.warn("Cannot read structure.info, using default INPX structure", e);
            return DEFAULT_STRUCTURE;
        }
    }

    private static Map<String, String> readArchives(ZipFile zip) {
        Map<String, String> result = new HashMap<>();
        ZipEntry entry = findIgnoreCase(zip, "archives.info");
        if (entry == null) return result;
        try (BufferedReader br = newDetectedReader(zip.getInputStream(entry))) {
            for (String line; (line = br.readLine()) != null;) {
                String candidate = extractArchiveCandidate(line);
                if (candidate == null) continue;
                result.putIfAbsent(stem(candidate), candidate.replace('\\', '/'));
            }
        } catch (Exception e) {
            log.warn("Cannot parse archives.info", e);
        }
        return result;
    }

    private static String extractArchiveCandidate(String line) {
        if (line == null) return null;
        String cleaned = line.replace("\uFEFF", "").trim();
        if (cleaned.isEmpty()) return null;
        for (String token : cleaned.split("[\\u0004;|\\t]")) {
            String v = token.trim().replace('\\', '/');
            String lower = v.toLowerCase(Locale.ROOT);
            for (String ext : ARCHIVE_EXTENSIONS) {
                if (lower.endsWith(ext)) return v;
            }
        }
        return null;
    }

    private static String resolveArchiveName(String inpName, Map<String, String> archivesByStem) {
        String base = stripExtension(Path.of(inpName).getFileName().toString());
        return archivesByStem.getOrDefault(base.toLowerCase(Locale.ROOT), base + ".zip");
    }

    private static String stem(String name) {
        String file = Path.of(name).getFileName().toString();
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

    private static ZipEntry findIgnoreCase(ZipFile zip, String wanted) {
        return zip.stream().filter(e -> Path.of(e.getName()).getFileName().toString().equalsIgnoreCase(wanted)).findFirst().orElse(null);
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
        if (isValidUtf8(sample)) return new DetectedEncoding(StandardCharsets.UTF_8, 0);

        Charset cp1251 = Charset.forName("windows-1251");
        Charset cp866 = Charset.forName("CP866");
        String a = new String(sample, cp1251);
        String b = new String(sample, cp866);
        return textScore(b) > textScore(a) + 4
                ? new DetectedEncoding(cp866, 0)
                : new DetectedEncoding(cp1251, 0);
    }

    private static boolean isValidUtf8(byte[] sample) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sample));
            return true;
        } catch (CharacterCodingException ignored) {
            return false;
        }
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
            if (c >= 0x2500 && c <= 0x259f) score -= 4; // box-drawing/block glyphs are typical of wrong CP866 decoding
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

    private static ZipFile openZip(Path path) throws IOException {
        IOException last = null;
        for (Charset cs : List.of(StandardCharsets.UTF_8, Charset.forName("CP866"), Charset.forName("windows-1251"))) {
            try { return new ZipFile(path.toFile(), cs); }
            catch (IOException e) { last = e; }
        }
        throw Objects.requireNonNull(last);
    }
}
