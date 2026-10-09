package com.myhomelibcorp.infrastructure.importengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedInpLineReaderTest {

    @Test
    void supportsEmptyLinesAndAllLineSeparatorsAcrossChunkBoundaries() throws Exception {
        String first = "x".repeat(8191);
        try (var reader = new InpxReader.BoundedInpLineReader(
                new StringReader(first + "\r\n\nsecond\rthird\nlast"))) {
            assertThat(reader.readLine()).isEqualTo(first);
            assertThat(reader.readLine()).isEmpty();
            assertThat(reader.readLine()).isEqualTo("second");
            assertThat(reader.readLine()).isEqualTo("third");
            assertThat(reader.readLine()).isEqualTo("last");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    void acceptsExactlyLimitAndRejectsOneCharacterBeyondIt() throws Exception {
        String maximum = "x".repeat(1_048_576);
        try (var reader = new InpxReader.BoundedInpLineReader(new StringReader(maximum + "\n"))) {
            assertThat(reader.readLine()).hasSize(1_048_576);
            assertThat(reader.readLine()).isNull();
        }
        try (var reader = new InpxReader.BoundedInpLineReader(new StringReader(maximum + "y\n"))) {
            assertThatThrownBy(reader::readLine).isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("INP_RECORD_TOO_LONG");
        }
    }

    @Test
    void standaloneInpImportStopsOnOversizedRowWithControlledError(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("corrupt.inp");
        Files.writeString(file, "x".repeat(1_048_577) + "\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new InpxReader().read(file))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("INP_RECORD_TOO_LONG: limit 1048576 characters");
    }
}
