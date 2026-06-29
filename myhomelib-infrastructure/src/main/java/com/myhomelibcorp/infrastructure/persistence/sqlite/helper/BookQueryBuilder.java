package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.Builder;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder для побудови динамічних SQL-запитів до таблиці books.
 */
@Component
public class BookQueryBuilder {

    public static class Query {
        private final StringBuilder sql = new StringBuilder("SELECT * FROM books b");
        private final List<Object> params = new ArrayList<>();
        private String orderBy = "ORDER BY b.title";
        private Integer limit;
        private Integer offset;

        public Query whereAuthorId(AuthorId authorId) {
            if (authorId == null) return this;
            sql.append(" JOIN book_authors ba ON b.id = ba.book_id");
            sql.append(" WHERE ba.author_id = ?");
            params.add(authorId.asString());
            return this;
        }

        public Query whereSeries(String seriesName) {
            if (seriesName == null || seriesName.isBlank()) return this;
            if (!params.isEmpty()) {
                sql.append(" AND");
            } else {
                sql.append(" WHERE");
            }
            sql.append(" b.series = ?");
            params.add(seriesName);
            return this;
        }

        public Query whereGenre(String genreCode) {
            if (genreCode == null || genreCode.isBlank()) return this;
            if (!params.isEmpty()) {
                sql.append(" AND");
            } else {
                sql.append(" WHERE");
            }
            sql.append(" EXISTS (SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id AND bg.genre_code = ?)");
            params.add(genreCode);
            return this;
        }

        public Query whereGroup(Long groupId) {
            if (groupId == null) return this;
            if (!params.isEmpty()) {
                sql.append(" AND");
            } else {
                sql.append(" WHERE");
            }
            sql.append(" EXISTS (SELECT 1 FROM book_groups bg WHERE bg.book_id = b.id AND bg.group_id = ?)");
            params.add(groupId);
            return this;
        }

        public Query whereTitleLike(String title) {
            if (title == null || title.isBlank()) return this;
            if (!params.isEmpty()) {
                sql.append(" AND");
            } else {
                sql.append(" WHERE");
            }
            sql.append(" lower(b.title) LIKE ?");
            params.add("%" + title.toLowerCase() + "%");
            return this;
        }

        public Query whereSeriesLike(String series) {
            if (series == null || series.isBlank()) return this;
            if (!params.isEmpty()) {
                sql.append(" AND");
            } else {
                sql.append(" WHERE");
            }
            sql.append(" lower(b.series) LIKE ?");
            params.add("%" + series.toLowerCase() + "%");
            return this;
        }

        public Query whereKeywordsLike(String keywords) {
            if (keywords == null || keywords.isBlank()) return this;
            if (!params.isEmpty()) {
                sql.append(" AND");
            } else {
                sql.append(" WHERE");
            }
            sql.append(" lower(b.keywords) LIKE ?");
            params.add("%" + keywords.toLowerCase() + "%");
            return this;
        }

        public Query whereAnnotationLike(String annotation) {
            if (annotation == null || annotation.isBlank()) return this;
            if (!params.isEmpty()) {
                sql.append(" AND");
            } else {
                sql.append(" WHERE");
            }
            sql.append(" lower(b.annotation) LIKE ?");
            params.add("%" + annotation.toLowerCase() + "%");
            return this;
        }

        public Query orderBy(String orderByClause) {
            this.orderBy = orderByClause != null ? orderByClause : "ORDER BY b.title";
            return this;
        }

        public Query limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Query offset(int offset) {
            this.offset = offset;
            return this;
        }

        public String getSql() {
            StringBuilder fullSql = new StringBuilder(sql);
            fullSql.append(" ").append(orderBy);
            if (limit != null) {
                fullSql.append(" LIMIT ?");
                params.add(limit);
                if (offset != null) {
                    fullSql.append(" OFFSET ?");
                    params.add(offset);
                }
            }
            return fullSql.toString();
        }

        public Object[] getParams() {
            return params.toArray();
        }
    }

    public static Query query() {
        return new Query();
    }
}