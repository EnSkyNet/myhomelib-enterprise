package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.mapper.BookListItemMapper;
import com.myhomelibcorp.application.port.out.repository.PageableBookQueryRepository;
import com.myhomelibcorp.application.query.book.PageableBookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqlitePageableBookQueryRepository implements PageableBookQueryRepository {

    private final QueryExecutor queryExecutor;
    private final BookRowMapper bookRowMapper;
    private final BookAuthorHelper bookAuthorHelper;
    private final BookGenreHelper bookGenreHelper;
    private final BookListItemMapper bookListItemMapper;

    @Override
    public PageResult<BookListItem> findPage(PageableBookQuery query) {
        // 1. Будуємо SQL з пагінацією
        SqlQuery sqlQuery = buildPagedQuery(query);
        List<Book> books = queryExecutor.query(sqlQuery.sql, bookRowMapper, sqlQuery.params);
        enrichBooks(books);

        // 2. Загальна кількість
        long total = count(query);

        // 3. Перетворюємо на DTO
        List<BookListItem> items = books.stream()
                .map(bookListItemMapper::toListItem)
                .toList();

        int page = query.pageRequest().getPage();
        int size = query.pageRequest().getSize();
        int totalPages = (int) Math.ceil((double) total / size);

        return new PageResult<>(items, total, totalPages, page, size);
    }

    @Override
    public long count(PageableBookQuery query) {
        SqlQuery countSql = buildCountQuery(query);
        return queryExecutor.queryForLong(countSql.sql, countSql.params);
    }

    private void enrichBooks(List<Book> books) {
        if (books.isEmpty()) return;
        bookAuthorHelper.loadAuthorsForBooks(books);
        bookGenreHelper.loadGenresForBooks(books);
    }

    /**
     * Будує SQL-запит з пагінацією на основі PageableBookQuery.
     * Це спрощена версія – у реальному проєкті слід динамічно додавати WHERE-умови.
     */
    private SqlQuery buildPagedQuery(PageableBookQuery query) {
        StringBuilder sql = new StringBuilder("SELECT b.* FROM books b ");
        List<Object> params = new ArrayList<>();

        // Додаємо JOIN для авторів, якщо потрібно
        if (query.authorId() != null) {
            sql.append("JOIN book_authors ba ON b.id = ba.book_id ");
        }

        sql.append("WHERE 1=1 ");

        // Умови
        if (query.authorId() != null) {
            sql.append("AND ba.author_id = ? ");
            params.add(query.authorId().asString());
        }
        if (query.seriesId() != null) {
            sql.append("AND b.series_id = ? ");
            params.add(query.seriesId().asString());
        }
        if (query.genreId() != null) {
            sql.append("AND EXISTS (SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id AND bg.genre_code = ?) ");
            params.add(query.genreId().asString());
        }
        if (query.language() != null) {
            sql.append("AND b.language = ? ");
            params.add(query.language().toString());
        }
        if (query.text() != null && !query.text().isBlank()) {
            String pattern = "%" + query.text().toLowerCase() + "%";
            sql.append("AND (LOWER(b.title) LIKE ? OR LOWER(b.keywords) LIKE ? OR LOWER(b.annotation) LIKE ?) ");
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        if (query.onlyRead()) {
            sql.append("AND b.progress = 100 ");
        }
        if (query.withoutSeries()) {
            sql.append("AND b.series IS NULL ");
        }
        if (query.withCover()) {
            sql.append("AND b.cover_hash IS NOT NULL ");
        }

        // Сортування
        String sortColumn = "b.title";
        switch (query.pageRequest().getSortBy()) {
            case TITLE -> sortColumn = "b.title";
            case AUTHOR -> sortColumn = "b.author_sort"; // або через JOIN
            case DATE -> sortColumn = "b.update_date";
            case RATING -> sortColumn = "b.rate";
            case RANDOM -> sortColumn = "RANDOM()";
        }
        sql.append("ORDER BY ").append(sortColumn).append(" ")
                .append(query.pageRequest().getDirection().name());

        // LIMIT / OFFSET
        sql.append(" LIMIT ? OFFSET ?");
        params.add(query.pageRequest().getSize());
        params.add(query.pageRequest().getOffset());

        return new SqlQuery(sql.toString(), params.toArray());
    }

    private SqlQuery buildCountQuery(PageableBookQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM books b ");
        List<Object> params = new ArrayList<>();

        if (query.authorId() != null) {
            sql.append("JOIN book_authors ba ON b.id = ba.book_id ");
        }
        sql.append("WHERE 1=1 ");
        if (query.authorId() != null) {
            sql.append("AND ba.author_id = ? ");
            params.add(query.authorId().asString());
        }
        if (query.seriesId() != null) {
            sql.append("AND b.series_id = ? ");
            params.add(query.seriesId().asString());
        }
        if (query.genreId() != null) {
            sql.append("AND EXISTS (SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id AND bg.genre_code = ?) ");
            params.add(query.genreId().asString());
        }
        if (query.language() != null) {
            sql.append("AND b.language = ? ");
            params.add(query.language().toString());
        }
        if (query.text() != null && !query.text().isBlank()) {
            String pattern = "%" + query.text().toLowerCase() + "%";
            sql.append("AND (LOWER(b.title) LIKE ? OR LOWER(b.keywords) LIKE ? OR LOWER(b.annotation) LIKE ?) ");
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        if (query.onlyRead()) {
            sql.append("AND b.progress = 100 ");
        }
        if (query.withoutSeries()) {
            sql.append("AND b.series IS NULL ");
        }
        if (query.withCover()) {
            sql.append("AND b.cover_hash IS NOT NULL ");
        }

        return new SqlQuery(sql.toString(), params.toArray());
    }

    private record SqlQuery(String sql, Object[] params) {}
}