package com.myhomelibcorp.infrastructure.importengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InpxReaderCompatibilityTest {
    private static final char D = 0x04;
    private final InpxReader reader = new InpxReader();

    @Test
    void readsMultipleInpWithCustomStructureArchivesDeletedAndDuplicateLibId(@TempDir Path dir) throws Exception {
        Path inpx = dir.resolve("catalog.inpx");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(inpx), StandardCharsets.UTF_8)) {
            put(out, "structure.info", ("TITLE" + D + "AUTHOR" + D + "FILE" + D + "EXT" + D + "LIBID" + D + "DEL\n").getBytes(StandardCharsets.UTF_8));
            put(out, "archives.info", ("books one" + D + "Архів з пробілами.zip\nsecond" + D + "каталог-2.7z\n").getBytes(StandardCharsets.UTF_8));
            put(out, "books one.inp", ("Перша" + D + "Шевченко,Тарас," + D + "one" + D + "fb2" + D + "42" + D + "0\n").getBytes(StandardCharsets.UTF_8));
            put(out, "second.inp", ("Друга" + D + "Франко,Іван," + D + "two" + D + "epub" + D + "42" + D + "1\n").getBytes(StandardCharsets.UTF_8));
        }

        List<InpxRecord> records = collect(reader.read(inpx));
        assertThat(records).hasSize(2);
        assertThat(records.get(0).field("TITLE")).isEqualTo("Перша");
        assertThat(records.get(0).archiveName()).isEqualTo("Архів з пробілами.zip");
        assertThat(records.get(1).field("TITLE")).isEqualTo("Друга");
        assertThat(records.get(1).archiveName()).isEqualTo("каталог-2.7z");
        assertThat(records).extracting(r -> r.field("LIBID")).containsExactly("42", "42");
        assertThat(records).extracting(r -> r.field("DEL")).containsExactly("0", "1");
    }

    @Test
    void fallsBackToDefaultStructureWhenStructureInfoIsMissing(@TempDir Path dir) throws Exception {
        Path inpx = dir.resolve("default.inpx");
        String line = String.join(String.valueOf(D),
                "Author,First,", "sf", "Title", "Series", "7", "book", "123", "lib-7", "0", "fb2", "2020-01-01", "uk", "space") + "\n";
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(inpx))) {
            put(out, "default.inp", line.getBytes(StandardCharsets.UTF_8));
        }

        InpxRecord record = reader.read(inpx).next();
        assertThat(record.field("AUTHOR")).isEqualTo("Author,First,");
        assertThat(record.field("GENRE")).isEqualTo("sf");
        assertThat(record.field("TITLE")).isEqualTo("Title");
        assertThat(record.field("LIBID")).isEqualTo("lib-7");
        assertThat(record.archiveName()).isEqualTo("default.zip");
    }

    @Test
    void infersFlibustaLibrateLayoutWhenStructureInfoIsMissing(@TempDir Path dir) throws Exception {
        Path inpx = dir.resolve("flibusta-no-structure.inpx");
        String line = String.join(String.valueOf(D),
                "Author,First,", "sf", "Title", "Series", "7", "book", "123", "lib-7", "0",
                "fb2", "2020-01-01", "uk", "3230", "space opera,AI") + D + "\n";
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(inpx))) {
            put(out, "online.inp", line.getBytes(StandardCharsets.UTF_8));
        }

        InpxRecord record = reader.read(inpx, true).next();
        assertThat(record.field("LIBRATE")).isEqualTo("3230");
        assertThat(record.field("KEYWORDS")).isEqualTo("space opera,AI");

        InpxBookNormalizer normalizer = new InpxBookNormalizer(new HashMap<>(), new HashMap<>());
        var normalized = normalizer.normalize(
                record, new HashMap<>(), new HashMap<>(), dir, new HashMap<>(), "test-source", true);
        assertThat(normalized).isNotNull();
        assertThat(normalized.row()[25]).isEqualTo(3230);
        assertThat(normalized.row()[9]).isEqualTo("space opera,AI");
    }

    @Test
    void keepsClassicNumericKeywordWithTrailingDelimiterWhenStructureInfoIsMissing(@TempDir Path dir) throws Exception {
        Path inpx = dir.resolve("classic-no-structure.inpx");
        String line = String.join(String.valueOf(D),
                "Author,First,", "sf", "Title", "Series", "7", "book", "123", "lib-7", "0",
                "fb2", "2020-01-01", "uk", "3230") + D + "\n";
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(inpx))) {
            put(out, "classic.inp", line.getBytes(StandardCharsets.UTF_8));
        }

        InpxRecord record = reader.read(inpx).next();
        assertThat(record.field("LIBRATE")).isEmpty();
        assertThat(record.field("KEYWORDS")).isEqualTo("3230");
    }

    @Test
    void decodesWindows1251InpAndMetadata(@TempDir Path dir) throws Exception {
        assertLegacyEncoding(dir, Charset.forName("windows-1251"), "Київська книга", "архів український.zip");
    }

    @Test
    void decodesCp866InpAndMetadata(@TempDir Path dir) throws Exception {
        assertLegacyEncoding(dir, Charset.forName("CP866"), "Русская книга", "архив русский.zip");
    }

    @Test
    void readsStandaloneInpWithoutLoadingWholeFile(@TempDir Path dir) throws Exception {
        Path inp = dir.resolve("standalone.inp");
        String row = String.join(String.valueOf(D),
                "Doe,John,", "prose", "Standalone", "", "0", "standalone", "10", "x", "0", "fb2", "2025", "en", "") + "\n";
        Files.writeString(inp, row, StandardCharsets.UTF_8);

        Iterator<InpxRecord> iterator = reader.read(inp);
        assertThat(iterator).hasNext();
        InpxRecord record = iterator.next();
        assertThat(record.field("TITLE")).isEqualTo("Standalone");
        assertThat(record.archiveName()).isEqualTo("standalone.zip");
        assertThat(iterator).isExhausted();
    }


    @Test
    void extraInpIsOnlineOnlyAndCountMatchesRead(@TempDir Path dir) throws Exception {
        Path inpx = dir.resolve("extra-policy.inpx");
        String main = String.join(String.valueOf(D),
                "Doe,John,", "prose", "Main", "", "0", "main", "10", "main-1", "0", "fb2", "2026", "en", "") + "\n";
        String extra = String.join(String.valueOf(D),
                "Roe,Jane,", "prose", "Extra", "", "0", "extra", "10", "extra-1", "0", "fb2", "2026", "en", "") + "\n";
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(inpx), StandardCharsets.UTF_8)) {
            put(out, "main.inp", main.getBytes(StandardCharsets.UTF_8));
            put(out, "extra.inp", extra.getBytes(StandardCharsets.UTF_8));
            put(out, "version.info", "20260828".getBytes(StandardCharsets.UTF_8));
            put(out, "collection.info", "metadata".getBytes(StandardCharsets.UTF_8));
            put(out, "unknown.txt", "not a book".getBytes(StandardCharsets.UTF_8));
        }

        List<InpxRecord> offline = collect(reader.read(inpx, false));
        List<InpxRecord> online = collect(reader.read(inpx, true));

        assertThat(offline).extracting(r -> r.field("TITLE")).containsExactly("Main");
        assertThat(online).extracting(r -> r.field("TITLE")).containsExactly("Extra", "Main");
        assertThat(reader.count(inpx, null, false)).isEqualTo(offline.size());
        assertThat(reader.count(inpx, null, true)).isEqualTo(online.size());
    }


    @Test
    void rejectsSuspiciouslyCompressedInpEntry(@TempDir Path dir) throws Exception {
        Path inpx = dir.resolve("suspicious.inpx");
        byte[] repetitive = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(repetitive, (byte) 'A');
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(inpx))) {
            put(out, "main.inp", repetitive);
        }

        assertThatThrownBy(() -> reader.count(inpx))
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("INPX");
    }

    private void assertLegacyEncoding(Path dir, Charset charset, String title, String archive) throws IOException {
        Path inpx = dir.resolve("legacy-" + charset.name().replaceAll("[^A-Za-z0-9]", "") + ".inpx");
        String structure = "TITLE" + D + "FILE" + D + "EXT" + D + "LIBID\n";
        String row = title + D + "book" + D + "fb2" + D + "77\n";
        String archives = "legacy" + D + archive + "\n";
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(inpx))) {
            put(out, "structure.info", structure.getBytes(charset));
            put(out, "archives.info", archives.getBytes(charset));
            put(out, "legacy.inp", row.getBytes(charset));
        }

        InpxRecord record = reader.read(inpx).next();
        assertThat(record.field("TITLE")).isEqualTo(title);
        assertThat(record.field("LIBID")).isEqualTo("77");
        assertThat(record.archiveName()).isEqualTo(archive);
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static List<InpxRecord> collect(Iterator<InpxRecord> iterator) {
        List<InpxRecord> result = new ArrayList<>();
        iterator.forEachRemaining(result::add);
        return result;
    }
}
