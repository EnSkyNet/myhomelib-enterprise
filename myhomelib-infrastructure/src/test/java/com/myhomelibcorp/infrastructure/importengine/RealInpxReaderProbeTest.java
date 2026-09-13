package com.myhomelibcorp.infrastructure.importengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in external corpus check. The catalog itself is never bundled with the source. */
@EnabledIfSystemProperty(named = "myhomelib.test.inpx", matches = ".+")
class RealInpxReaderProbeTest {
    @Test
    void readsTheEntireProvidedOnlineCatalogWithoutRetainingAllRows() throws Exception {
        Path path = Path.of(System.getProperty("myhomelib.test.inpx"));
        assertThat(Files.isRegularFile(path)).isTrue();
        InpxReader reader = new InpxReader();
        long started = System.nanoTime();
        long counted = reader.count(path, new AtomicBoolean(), true);
        long parsed = 0, emptyTitles = 0, replacementCharacters = 0, libraryRatings = 0;
        MessageDigest titles = MessageDigest.getInstance("SHA-256");
        var records = reader.read(path, true);
        try {
            while (records.hasNext()) {
                InpxRecord row = records.next();
                parsed++;
                if (row.field("TITLE").isBlank()) emptyTitles++;
                if (row.field("TITLE").indexOf('\ufffd') >= 0) replacementCharacters++;
                if (!row.field("LIBRATE").isBlank()) libraryRatings++;
                titles.update(row.field("TITLE").getBytes(StandardCharsets.UTF_8));
                titles.update((byte) 0);
            }
        } finally {
            InpxReader.closeIterator(records);
        }
        assertThat(parsed).isPositive().isEqualTo(counted);
        String titleHash = HexFormat.of().formatHex(titles.digest());
        String expectedHash = System.getProperty("myhomelib.test.inpx.expectedTitleSha256");
        if (expectedHash != null) assertThat(titleHash).isEqualTo(expectedHash);
        String expected = System.getProperty("myhomelib.test.inpx.expectedRecords");
        if (expected != null) assertThat(parsed).isEqualTo(Long.parseLong(expected));
        System.out.printf(java.util.Locale.ROOT,
                "INPX_PROBE records=%d emptyTitles=%d replacementCharacters=%d libraryRatings=%d titleSha256=%s elapsedMs=%.1f%n",
                parsed, emptyTitles, replacementCharacters, libraryRatings, titleHash, (System.nanoTime() - started) / 1_000_000.0);
    }
}
