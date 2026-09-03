package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.query.search.SearchMode;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure request/query construction extracted from the JavaFX controller. */
final class SearchQueryFactory {
    private SearchQueryFactory() { }

    static SearchRequest basic(String query, int pageSize, int offset) {
        return SearchRequest.builder()
                .text(query == null ? "" : query.trim())
                .limit(pageSize)
                .offset(Math.max(0, offset))
                .mode(SearchMode.PHRASE)
                .build();
    }

    static SearchRequest advanced(SearchFormInput form, int pageSize, int offset) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .text(textQuery(form))
                .ratingFrom(integer(form.ratingFrom()))
                .ratingTo(integer(form.ratingTo()))
                .yearFrom(integer(form.yearFrom()))
                .yearTo(integer(form.yearTo()))
                .addedFrom(form.addedFrom())
                .addedTo(form.addedTo())
                .localOnly(form.localOnly() ? Boolean.TRUE : null)
                .limit(pageSize)
                .offset(Math.max(0, offset))
                .mode(SearchMode.PHRASE);
        if (!form.language().isBlank()) {
            try {
                builder.language(LanguageCode.of(form.language()));
            } catch (IllegalArgumentException ignored) {
                // Invalid user-entered language is simply not applied as a structured filter.
            }
        }
        return builder.build();
    }

    static boolean hasAdvancedFilters(SearchFormInput form) {
        return !form.title().isBlank() || !form.author().isBlank() || !form.series().isBlank()
                || !form.genre().isBlank() || !form.keyword().isBlank() || !form.annotation().isBlank()
                || !form.file().isBlank() || !form.language().isBlank() || !form.ratingFrom().isBlank()
                || !form.ratingTo().isBlank() || !form.yearFrom().isBlank() || !form.yearTo().isBlank()
                || form.addedFrom() != null || form.addedTo() != null || form.localOnly();
    }

    static String savedQuery(SearchFormInput form) {
        List<String> clauses = new ArrayList<>();
        String textual = textQuery(form);
        if (!textual.isBlank()) clauses.add(textual);
        if (!form.language().isBlank()) clauses.add("language:" + quote(form.language()));
        Integer ratingFrom = integer(form.ratingFrom());
        Integer ratingTo = integer(form.ratingTo());
        if (ratingFrom != null || ratingTo != null) {
            clauses.add("library_rate:[" + (ratingFrom == null ? "0" : ratingFrom)
                    + " TO " + (ratingTo == null ? "5" : ratingTo) + "]");
        }
        Integer yearFrom = integer(form.yearFrom());
        Integer yearTo = integer(form.yearTo());
        if (yearFrom != null || yearTo != null) {
            clauses.add("year:[" + padYear(yearFrom == null ? 0 : yearFrom)
                    + " TO " + padYear(yearTo == null ? 9999 : yearTo) + "]");
        }
        if (form.addedFrom() != null || form.addedTo() != null) {
            clauses.add("created:[" + formatDate(form.addedFrom(), "00000000")
                    + " TO " + formatDate(form.addedTo(), "99999999") + "]");
        }
        if (form.localOnly()) clauses.add("local:1");
        return String.join(" AND ", clauses);
    }

    static String textQuery(SearchFormInput form) {
        List<String> clauses = new ArrayList<>();
        if (!form.freeText().isBlank()) clauses.add(form.freeText());
        addFieldClause(clauses, "title", form.title());
        addFieldClause(clauses, "authors", form.author());
        addFieldClause(clauses, "series", form.series());
        addFieldClause(clauses, "genres", form.genre());
        addFieldClause(clauses, "keywords", form.keyword());
        addFieldClause(clauses, "annotation", form.annotation());
        addFieldClause(clauses, "file_name", form.file());
        return String.join(" AND ", clauses);
    }

    private static void addFieldClause(List<String> clauses, String field, String value) {
        if (value == null || value.isBlank()) return;
        String v = value.trim();
        if (v.startsWith("%") && v.endsWith("%") && v.length() > 2) {
            clauses.add(field + ":*" + escape(v.substring(1, v.length() - 1)) + "*");
        } else if (v.startsWith("=\"") && v.endsWith("\"") && v.length() >= 3) {
            clauses.add(field + ":" + v.substring(1));
        } else if (v.toUpperCase(Locale.ROOT).contains(" OR ")) {
            String[] parts = v.split("(?i)\\s+OR\\s+");
            List<String> alternatives = new ArrayList<>(parts.length);
            for (String part : parts) alternatives.add(field + ":" + quote(part));
            clauses.add("(" + String.join(" OR ", alternatives) + ")");
        } else {
            clauses.add(field + ":" + quote(v));
        }
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String quote(String value) { return "\"" + escape(value) + "\""; }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static String padYear(int year) { return String.format(Locale.ROOT, "%04d", Math.max(0, Math.min(9999, year))); }
    private static String formatDate(LocalDate date, String fallback) {
        return date == null ? fallback : date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
