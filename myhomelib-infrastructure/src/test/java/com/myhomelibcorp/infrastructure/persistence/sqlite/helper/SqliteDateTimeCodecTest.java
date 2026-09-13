package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteDateTimeCodecTest {

    @Test
    void parsesNativeSqliteCurrentTimestampWithoutMilliseconds() {
        assertThat(SqliteDateTimeCodec.parse("2026-09-05 18:37:45"))
                .isEqualTo(LocalDateTime.of(2026, 9, 5, 18, 37, 45));
    }

    @Test
    void parsesApplicationTimestampWithMilliseconds() {
        assertThat(SqliteDateTimeCodec.parse("2026-09-05 18:37:45.123"))
                .isEqualTo(LocalDateTime.of(2026, 9, 5, 18, 37, 45, 123_000_000));
    }

    @Test
    void parsesSqliteTimestampWithHigherFractionalPrecision() {
        assertThat(SqliteDateTimeCodec.parse("2026-09-05 18:37:45.123456"))
                .isEqualTo(LocalDateTime.of(2026, 9, 5, 18, 37, 45, 123_456_000));
    }

    @Test
    void preservesIsoLocalDateTimeCompatibility() {
        assertThat(SqliteDateTimeCodec.parse("2026-09-05T18:37:45"))
                .isEqualTo(LocalDateTime.of(2026, 9, 5, 18, 37, 45));
    }

    @Test
    void formatRemainsCanonicalMilliseconds() {
        assertThat(SqliteDateTimeCodec.format(LocalDateTime.of(2026, 9, 5, 18, 37, 45, 123_000_000)))
                .isEqualTo("2026-09-05 18:37:45.123");
    }
}
