package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqlQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BookQueryBuilder {

    public SqlQuery build(BookQuery query) {
        var context = new QueryContext();
        addJoins(context, query);
        addConditions(context, query);
        String sql = buildSelectSql(context, query);
        return SqlQuery.of(sql, context.params.toArray());
    }

    public SqlQuery buildCount(BookQuery query) {
        var context = new QueryContext();
        addJoins(context, query);
        addConditions(context, query);
        String sql = buildCountSql(context);
        return SqlQuery.of(sql, context.params.toArray());
    }

    private void addJoins(QueryContext ctx, BookQuery query) {
        if (query.authorId() != null) {
            ctx.joins.add("JOIN book_authors ba ON b.id = ba.book_id");
        }
        if (query.onlyInHistory()) {
            ctx.joins.add("JOIN reading_history rh ON rh.book_id = b.id");
        }
        if (query.onlyFavorites()) {
            ctx.conditions.add("EXISTS (SELECT 1 FROM book_groups bgf JOIN groups gf ON gf.id = bgf.group_id " +
                    "WHERE bgf.book_id = b.id AND LOWER(TRIM(gf.name)) IN ('favorites','обране','избрани','улюблене'))");
        }
        // FIX: JOIN з таблицею series з урахуванням регістру та пробілів
        if (query.seriesId() != null) {
            ctx.joins.add("JOIN series s ON LOWER(TRIM(b.series)) = LOWER(TRIM(s.name))");
        }
        if (query.groupId() != null) {
            ctx.joins.add("JOIN book_groups qbg_group ON qbg_group.book_id = b.id");
        }
    }

    private void addConditions(QueryContext ctx, BookQuery query) {
        // Deleted rows are tombstones used by catalogue updates and must never leak into normal browsing.
        ctx.conditions.add("b.deleted = 0");

        // AUTHOR
        if (query.authorId() != null) {
            ctx.conditions.add("ba.author_id = ?");
            ctx.params.add(query.authorId().asString());
        }

        // SERIES – використовуємо s.id після JOIN
        if (query.seriesId() != null) {
            ctx.conditions.add("s.id = ?");
            ctx.params.add(query.seriesId().asString());
        }

        // GENRE
        if (query.genreId() != null) {
            ctx.conditions.add("EXISTS (SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id AND bg.genre_code = ?)");
            ctx.params.add(query.genreId().asString());
        }

        // EXACT KEYWORD TOKEN (comma/semicolon/pipe separated metadata)
        if (query.keyword() != null) {
            ctx.conditions.add("""
                    EXISTS (
                        WITH RECURSIVE split(rest, token) AS (
                            VALUES(REPLACE(REPLACE(COALESCE(b.keywords, ''), ';', ','), '|', ',') || ',', '')
                            UNION ALL
                            SELECT SUBSTR(rest, INSTR(rest, ',') + 1),
                                   TRIM(SUBSTR(rest, 1, INSTR(rest, ',') - 1))
                            FROM split
                            WHERE rest <> ''
                        )
                        SELECT 1 FROM split WHERE LOWER(token) = LOWER(?) LIMIT 1
                    )
                    """);
            ctx.params.add(query.keyword());
        }

        // PUBLISHER (exact logical navigation/filter; do not emulate it through title/annotation search)
        if (query.publisher() != null) {
            ctx.conditions.add("LOWER(TRIM(COALESCE(b.publisher, ''))) = LOWER(TRIM(?))");
            ctx.params.add(query.publisher());
        }

        // LANGUAGE
        if (query.language() != null) {
            ctx.conditions.add("LOWER(TRIM(b.language)) = LOWER(?)");
            ctx.params.add(query.language().toString());
        }

        // FORMAT
        if (query.format() != null) {
            ctx.conditions.add("b.format = ?");
            ctx.params.add(query.format().name());
        }

        // YEAR
        if (query.year() != null) {
            ctx.conditions.add("b.year = ?");
            ctx.params.add(query.year());
        }

        // ARCHIVE CONTAINER
        if (query.archivePath() != null) {
            ctx.conditions.add("COALESCE(b.archive_entry, '') <> ''");
            ctx.conditions.add("LOWER(REPLACE(COALESCE(b.folder, ''), '\\', '/')) = LOWER(?)");
            ctx.params.add(normalizePath(query.archivePath()));
            if (query.archiveCollectionRoot() != null) {
                ctx.conditions.add("LOWER(REPLACE(COALESCE(b.collection_root, ''), '\\', '/')) = LOWER(?)");
                ctx.params.add(normalizePath(query.archiveCollectionRoot()));
            }
        }

        // TEXT SEARCH
        if (query.text() != null && !query.text().isBlank()) {
            String pattern = "%" + query.text().toLowerCase() + "%";
            ctx.conditions.add("(LOWER(b.title) LIKE ? OR LOWER(b.keywords) LIKE ? OR LOWER(b.annotation) LIKE ?)");
            ctx.params.add(pattern);
            ctx.params.add(pattern);
            ctx.params.add(pattern);
        }

        // ONLY READ
        if (query.onlyRead()) {
            ctx.conditions.add("b.progress = 100");
        }

        // USER RATING / REVIEW SUBSETS
        if (query.onlyRated()) {
            ctx.conditions.add("COALESCE(b.rate, 0) > 0");
        }
        if (query.onlyReviewed()) {
            ctx.conditions.add("b.review IS NOT NULL AND TRIM(b.review) <> ''");
        }

        // WITHOUT SERIES
        if (query.withoutSeries()) {
            ctx.conditions.add("b.series IS NULL");
        }

        // WITH COVER
        if (query.withCover()) {
            ctx.conditions.add("b.cover_hash IS NOT NULL");
        }

        // GROUP - joined through idx_book_groups_group_book so large groups do not
        // require a full books scan merely to count/enumerate membership.
        if (query.groupId() != null) {
            ctx.conditions.add("qbg_group.group_id = ?");
            ctx.params.add(query.groupId().asLong());
        }

        addUnifiedFilter(ctx, query.filterSpec());
    }

    private void addUnifiedFilter(QueryContext ctx, BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql translated = BookFilterSqlAdapter.build(filter, "b");
        if (translated.isEmpty()) return;
        ctx.conditions.add(translated.clause());
        ctx.params.addAll(translated.params());
    }

    private String buildSelectSql(QueryContext ctx, BookQuery query) {
        StringBuilder sql = new StringBuilder("SELECT b.* FROM books b");
        for (String join : ctx.joins) {
            sql.append(" ").append(join);
        }
        if (!ctx.conditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", ctx.conditions));
        }
        sql.append(" ").append(buildOrderBy(query));
        sql.append(" LIMIT ? OFFSET ?");
        ctx.params.add(query.pagination().limit());
        ctx.params.add(query.pagination().offset());
        return sql.toString();
    }

    private String buildCountSql(QueryContext ctx) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM books b");
        for (String join : ctx.joins) {
            sql.append(" ").append(join);
        }
        if (!ctx.conditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", ctx.conditions));
        }
        return sql.toString();
    }

    private String buildOrderBy(BookQuery query) {
        if (query.onlyInHistory()) {
            return "ORDER BY rh.last_opened_at DESC, b.id ASC";
        }
        SortBy sortBy = query.sortBy();
        SortDirection direction = query.direction();
        String dir = (direction == SortDirection.DESC) ? "DESC" : "ASC";
        String column;
        switch (sortBy) {
            case TITLE:    column = "b.title"; break;
            case AUTHOR:   column = "b.author_sort"; break;
            case DATE:     column = "b.update_date"; break;
            case RATING:   column = "b.rate"; break;
            case YEAR:     column = "COALESCE(b.year, 0)"; break;
            case SERIES:   column = "LOWER(COALESCE(b.series, ''))"; break;
            case RANDOM:   column = "RANDOM()"; break;
            default:       column = "b.title";
        }
        if (sortBy == SortBy.RANDOM) {
            return "ORDER BY RANDOM()";
        }
        if (sortBy == SortBy.SERIES) {
            // Series view semantics: named series first, books in a series by their declared number,
            // unnumbered entries at the end of the series, and books without a series last.
            return "ORDER BY CASE WHEN TRIM(COALESCE(b.series, '')) = '' THEN 1 ELSE 0 END ASC, "
                    + "LOWER(TRIM(COALESCE(b.series, ''))) " + dir + ", "
                    + "CASE WHEN COALESCE(b.sequence_number, 0) > 0 THEN b.sequence_number ELSE 2147483647 END ASC, "
                    + "LOWER(COALESCE(b.title, '')) ASC, b.id ASC";
        }
        // Stable tie-break is required for offset paging and full Lucene rebuilds.
        return "ORDER BY " + column + " " + dir + ", b.id " + dir;
    }


    private static String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static class QueryContext {
        final List<String> joins = new ArrayList<>();
        final List<String> conditions = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
    }
}