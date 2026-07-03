package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqlQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Адаптер між {@link BookQuery} і SQL-запитом до таблиці books.
 * <p>
 * Перетворює об'єкт критеріїв на готовий SQL-запит з параметрами.
 * Не зберігає стан, є thread-safe.
 */
@Component
public class BookQueryBuilder {

    /**
     * Будує SELECT-запит для отримання списку книг.
     *
     * @param query об'єкт з критеріями пошуку, сортуванням і пагінацією
     * @return {@link SqlQuery} з SQL-рядком і масивом параметрів
     */
    public SqlQuery build(BookQuery query) {
        var context = new QueryContext();
        addJoins(context, query);
        addConditions(context, query);
        String sql = buildSelectSql(context, query);
        return SqlQuery.of(sql, context.params.toArray());
    }

    /**
     * Будує COUNT-запит для підрахунку кількості книг за критеріями.
     *
     * @param query об'єкт з критеріями пошуку (пагінація і сортування ігноруються)
     * @return {@link SqlQuery} з SQL-рядком і масивом параметрів
     */
    public SqlQuery buildCount(BookQuery query) {
        var context = new QueryContext();
        addJoins(context, query);
        addConditions(context, query);
        String sql = buildCountSql(context);
        return SqlQuery.of(sql, context.params.toArray());
    }

    // ==================== ПРИВАТНІ ДОПОМІЖНІ МЕТОДИ ====================

    private void addJoins(QueryContext ctx, BookQuery query) {
        if (query.authorId() != null) {
            ctx.joins.add("JOIN book_authors ba ON b.id = ba.book_id");
        }
        if (query.onlyFavorites()) {
            ctx.joins.add("JOIN book_groups bgf ON b.id = bgf.book_id AND bgf.group_id = 1");
        }
        // Можна додати інші JOIN при необхідності
    }

    private void addConditions(QueryContext ctx, BookQuery query) {
        // AUTHOR
        if (query.authorId() != null) {
            ctx.conditions.add("ba.author_id = ?");
            ctx.params.add(query.authorId().asString());
        }

        // SERIES
        if (query.seriesId() != null) {
            ctx.conditions.add("b.series_id = ?");
            ctx.params.add(query.seriesId().asString());
        }

        // GENRE (через підзапит)
        if (query.genreId() != null) {
            ctx.conditions.add("EXISTS (SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id AND bg.genre_code = ?)");
            ctx.params.add(query.genreId().asString());
        }

        // LANGUAGE
        if (query.language() != null) {
            ctx.conditions.add("b.language = ?");
            ctx.params.add(query.language().toString());
        }

        // FORMAT
        if (query.format() != null) {
            ctx.conditions.add("b.format = ?");
            ctx.params.add(query.format().name());
        }

        // TEXT SEARCH (OR across multiple fields)
        if (query.text() != null && !query.text().isBlank()) {
            String pattern = "%" + query.text().toLowerCase() + "%";
            ctx.conditions.add("(LOWER(b.title) LIKE ? OR LOWER(b.keywords) LIKE ? OR LOWER(b.annotation) LIKE ?)");
            ctx.params.add(pattern);
            ctx.params.add(pattern);
            ctx.params.add(pattern);
        }

        // ONLY READ (progress == 100)
        if (query.onlyRead()) {
            ctx.conditions.add("b.progress = 100");
        }

        // WITHOUT SERIES
        if (query.withoutSeries()) {
            ctx.conditions.add("b.series IS NULL");
        }

        // WITH COVER
        if (query.withCover()) {
            ctx.conditions.add("b.cover_id IS NOT NULL");
        }

        // GROUP (якщо потрібно, хоча в основному використовується окремий метод у UseCase)
        if (query.groupId() != null) {
            ctx.conditions.add("EXISTS (SELECT 1 FROM book_groups bg WHERE bg.book_id = b.id AND bg.group_id = ?)");
            ctx.params.add(query.groupId().asLong());
        }
    }

    private String buildSelectSql(QueryContext ctx, BookQuery query) {
        StringBuilder sql = new StringBuilder("SELECT b.* FROM books b");

        // JOIN
        for (String join : ctx.joins) {
            sql.append(" ").append(join);
        }

        // WHERE
        if (!ctx.conditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", ctx.conditions));
        }

        // ORDER BY
        sql.append(" ").append(buildOrderBy(query.sortBy(), query.direction()));

        // LIMIT / OFFSET
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

    private String buildOrderBy(SortBy sortBy, SortDirection direction) {
        String dir = (direction == SortDirection.DESC) ? "DESC" : "ASC";
        String column;
        switch (sortBy) {
            case TITLE:    column = "b.title"; break;
            case AUTHOR:   column = "b.author_sort"; break; // або через JOIN, але спростимо
            case DATE:     column = "b.update_date"; break;
            case RATING:   column = "b.rate"; break;
            case RANDOM:   column = "RANDOM()"; break;
            default:       column = "b.title";
        }
        return "ORDER BY " + column + " " + dir;
    }

    // ==================== ВНУТРІШНІЙ КЛАС ДЛЯ КОНТЕКСТУ ====================

    private static class QueryContext {
        final List<String> joins = new ArrayList<>();
        final List<String> conditions = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
    }
}