package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/** Canonical codec for SQLite timestamp text used across persistence mappings. */
@Slf4j
public final class SqliteDateTimeCodec {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter SQLITE_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter(Locale.ROOT);

    private SqliteDateTimeCodec() { }

    public static String format(LocalDateTime value) {
        return value == null ? null : value.format(FORMATTER);
    }

    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            // SQLite CURRENT_TIMESTAMP uses `yyyy-MM-dd HH:mm:ss`, while values written
            // by the application may include fractional seconds. Accept both forms.
            return LocalDateTime.parse(value, SQLITE_FORMATTER);
        } catch (RuntimeException sqliteFormatFailure) {
            try {
                // Keep backward compatibility with ISO_LOCAL_DATE_TIME (`T` separator).
                return LocalDateTime.parse(value);
            } catch (RuntimeException invalid) {
                log.warn("Failed to parse SQLite date: {}", value);
                return null;
            }
        }
    }
}
