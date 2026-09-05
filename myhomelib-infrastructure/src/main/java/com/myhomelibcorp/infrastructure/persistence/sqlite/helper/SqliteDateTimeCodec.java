package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Canonical codec for SQLite timestamp text used across persistence mappings. */
@Slf4j
public final class SqliteDateTimeCodec {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private SqliteDateTimeCodec() { }

    public static String format(LocalDateTime value) {
        return value == null ? null : value.format(FORMATTER);
    }

    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value, FORMATTER);
        } catch (RuntimeException legacyFormatFailure) {
            try {
                return LocalDateTime.parse(value);
            } catch (RuntimeException invalid) {
                log.warn("Failed to parse SQLite date: {}", value);
                return null;
            }
        }
    }
}
