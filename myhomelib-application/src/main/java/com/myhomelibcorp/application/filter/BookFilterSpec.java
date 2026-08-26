package com.myhomelibcorp.application.filter;

import com.myhomelibcorp.application.query.book.BookFormat;

import java.util.Locale;

/**
 * Stage 8 unified, UI-agnostic book filter shared by SQL navigation/table queries and Lucene search.
 * Base navigation/search criteria are always AND-ed with this filter group. Criteria inside the group
 * use {@link BookFilterMode#AND} or {@link BookFilterMode#OR}.
 */
public record BookFilterSpec(
        BookFilterMode mode,
        String language,
        Integer yearFrom,
        Integer yearTo,
        BookFormat format,
        Boolean local,
        Boolean read,
        Integer ratingMin,
        Integer ratingMax,
        boolean hideUnrated,
        BookQuickFilterField quickField,
        String quickValue
) {
    public BookFilterSpec {
        mode = mode == null ? BookFilterMode.AND : mode;
        language = normalize(language);
        if (language != null) language = language.toLowerCase(Locale.ROOT);
        yearFrom = positive(yearFrom);
        yearTo = positive(yearTo);
        if (yearFrom != null && yearTo != null && yearFrom > yearTo) {
            int tmp = yearFrom; yearFrom = yearTo; yearTo = tmp;
        }
        ratingMin = rating(ratingMin);
        ratingMax = rating(ratingMax);
        if (ratingMin != null && ratingMax != null && ratingMin > ratingMax) {
            int tmp = ratingMin; ratingMin = ratingMax; ratingMax = tmp;
        }
        quickField = quickField == null ? BookQuickFilterField.ANY : quickField;
        quickValue = normalize(quickValue);
    }

    public static BookFilterSpec empty() {
        return new BookFilterSpec(BookFilterMode.AND, null, null, null, null,
                null, null, null, null, false, BookQuickFilterField.ANY, null);
    }

    public boolean isActive() { return activeCriteriaCount() > 0; }

    public int activeCriteriaCount() {
        int count = 0;
        if (language != null) count++;
        if (yearFrom != null || yearTo != null) count++;
        if (format != null) count++;
        if (local != null) count++;
        if (read != null) count++;
        if (ratingMin != null || ratingMax != null) count++;
        if (hideUnrated) count++;
        if (quickValue != null) count++;
        return count;
    }

    public BookFilterSpec withQuickFilter(BookQuickFilterField field, String value) {
        return new BookFilterSpec(mode, language, yearFrom, yearTo, format, local, read,
                ratingMin, ratingMax, hideUnrated, field, value);
    }

    public BookFilterSpec withoutQuickFilter() {
        return withQuickFilter(BookQuickFilterField.ANY, null);
    }

    public String cacheKey() {
        return mode + "|" + safe(language) + "|" + safe(yearFrom) + "|" + safe(yearTo) + "|"
                + safe(format) + "|" + safe(local) + "|" + safe(read) + "|" + safe(ratingMin) + "|"
                + safe(ratingMax) + "|" + hideUnrated + "|" + quickField + "|" + safe(quickValue);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
    private static Integer positive(Integer value) { return value == null || value <= 0 ? null : value; }
    private static Integer rating(Integer value) {
        if (value == null) return null;
        return Math.max(0, Math.min(5, value));
    }
    private static String safe(Object value) { return value == null ? "" : value.toString(); }
}
