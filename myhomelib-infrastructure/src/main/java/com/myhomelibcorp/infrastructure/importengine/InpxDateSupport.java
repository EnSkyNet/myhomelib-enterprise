package com.myhomelibcorp.infrastructure.importengine;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;

/** Date semantics shared by all INP/INPX import paths. */
public final class InpxDateSupport {
    private static final DateTimeFormatter SPACE_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter(Locale.ROOT);

    private InpxDateSupport() { }

    /**
     * Parses the INPX DATE field as source/catalog update time.
     * A date without a time is represented at local midnight; invalid/blank values stay unknown.
     */
    public static LocalDateTime parseCatalogDate(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try { return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(text, SPACE_DATE_TIME); }
        catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime(); }
        catch (DateTimeParseException ignored) { return null; }
    }
}
