package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.filter.BookFilterMode;
import com.myhomelibcorp.application.filter.BookFilterSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single SQLite translation of the Stage 8 unified filter. Both book queries and
 * navigation facets use this adapter so counts and rows cannot silently diverge.
 */
public final class BookFilterSqlAdapter {
    private BookFilterSqlAdapter() { }

    public static FilterSql build(BookFilterSpec filter, String alias) {
        if (filter == null || !filter.isActive()) return FilterSql.empty();
        String b = alias == null || alias.isBlank() ? "b" : alias.trim();
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (filter.language() != null) {
            conditions.add("LOWER(TRIM(COALESCE(" + b + ".language, ''))) = LOWER(?)");
            params.add(filter.language());
        }
        if (filter.yearFrom() != null || filter.yearTo() != null) {
            if (filter.yearFrom() != null && filter.yearTo() != null) {
                conditions.add(b + ".year BETWEEN ? AND ?");
                params.add(filter.yearFrom());
                params.add(filter.yearTo());
            } else if (filter.yearFrom() != null) {
                conditions.add(b + ".year >= ?");
                params.add(filter.yearFrom());
            } else {
                conditions.add(b + ".year <= ?");
                params.add(filter.yearTo());
            }
        }
        if (filter.format() != null) {
            conditions.add("UPPER(COALESCE(" + b + ".format, '')) = ?");
            params.add(filter.format().name());
        }
        if (filter.local() != null) {
            conditions.add("COALESCE(" + b + ".local, 0) = ?");
            params.add(filter.local() ? 1 : 0);
        }
        if (filter.read() != null) {
            conditions.add(filter.read()
                    ? "COALESCE(" + b + ".progress, 0) >= 100"
                    : "COALESCE(" + b + ".progress, 0) < 100");
        }
        if (filter.ratingMin() != null || filter.ratingMax() != null) {
            if (filter.ratingMin() != null && filter.ratingMax() != null) {
                conditions.add("COALESCE(" + b + ".rate, 0) BETWEEN ? AND ?");
                params.add(filter.ratingMin());
                params.add(filter.ratingMax());
            } else if (filter.ratingMin() != null) {
                conditions.add("COALESCE(" + b + ".rate, 0) >= ?");
                params.add(filter.ratingMin());
            } else {
                conditions.add("COALESCE(" + b + ".rate, 0) <= ?");
                params.add(filter.ratingMax());
            }
        }
        if (filter.hideUnrated()) {
            conditions.add("COALESCE(" + b + ".rate, 0) > 0");
        }
        if (filter.quickValue() != null) {
            List<String> tokenConditions = new ArrayList<>();
            for (String token : filter.quickValue().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (token.isBlank()) continue;
                String like = "%" + escapeLike(token) + "%";
                switch (filter.quickField()) {
                    case TITLE -> {
                        tokenConditions.add("LOWER(COALESCE(" + b + ".title, '')) LIKE ? ESCAPE '\\'");
                        params.add(like);
                    }
                    case AUTHOR -> {
                        tokenConditions.add("EXISTS (SELECT 1 FROM book_authors qba JOIN authors qa ON qa.id = qba.author_id " +
                                "WHERE qba.book_id = " + b + ".id AND LOWER(TRIM(COALESCE(qa.last_name,'') || ' ' || COALESCE(qa.first_name,'') || ' ' || COALESCE(qa.middle_name,''))) LIKE ? ESCAPE '\\')");
                        params.add(like);
                    }
                    case SERIES -> {
                        tokenConditions.add("LOWER(COALESCE(" + b + ".series, '')) LIKE ? ESCAPE '\\'");
                        params.add(like);
                    }
                    case GENRE -> {
                        tokenConditions.add("EXISTS (SELECT 1 FROM book_genres qbg JOIN genres qg ON qg.code = qbg.genre_code " +
                                "WHERE qbg.book_id = " + b + ".id AND (LOWER(COALESCE(qg.name,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(qbg.genre_code,'')) LIKE ? ESCAPE '\\'))");
                        params.add(like); params.add(like);
                    }
                    case KEYWORD -> {
                        tokenConditions.add("LOWER(COALESCE(" + b + ".keywords, '')) LIKE ? ESCAPE '\\'");
                        params.add(like);
                    }
                    case PUBLISHER -> {
                        tokenConditions.add("LOWER(COALESCE(" + b + ".publisher, '')) LIKE ? ESCAPE '\\'");
                        params.add(like);
                    }
                    case FILE -> {
                        tokenConditions.add("LOWER(COALESCE(" + b + ".file_name, '')) LIKE ? ESCAPE '\\'");
                        params.add(like);
                    }
                    case ANY -> {
                        tokenConditions.add("(LOWER(COALESCE(" + b + ".title,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(" + b + ".series,'')) LIKE ? ESCAPE '\\' " +
                                "OR LOWER(COALESCE(" + b + ".keywords,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(" + b + ".publisher,'')) LIKE ? ESCAPE '\\' " +
                                "OR LOWER(COALESCE(" + b + ".file_name,'')) LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM book_authors qba JOIN authors qa ON qa.id=qba.author_id " +
                                "WHERE qba.book_id=" + b + ".id AND LOWER(TRIM(COALESCE(qa.last_name,'') || ' ' || COALESCE(qa.first_name,'') || ' ' || COALESCE(qa.middle_name,''))) LIKE ? ESCAPE '\\') " +
                                "OR EXISTS (SELECT 1 FROM book_genres qbg JOIN genres qg ON qg.code=qbg.genre_code WHERE qbg.book_id=" + b + ".id " +
                                "AND (LOWER(COALESCE(qg.name,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(qbg.genre_code,'')) LIKE ? ESCAPE '\\')))" );
                        for (int i = 0; i < 8; i++) params.add(like);
                    }
                }
            }
            if (!tokenConditions.isEmpty()) conditions.add("(" + String.join(" AND ", tokenConditions) + ")");
        }

        if (conditions.isEmpty()) return FilterSql.empty();
        String joiner = filter.mode() == BookFilterMode.OR ? " OR " : " AND ";
        return new FilterSql("(" + String.join(joiner, conditions) + ")", List.copyOf(params));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public record FilterSql(String clause, List<Object> params) {
        public static FilterSql empty() { return new FilterSql("", List.of()); }
        public boolean isEmpty() { return clause == null || clause.isBlank(); }
    }
}
