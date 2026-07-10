package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqliteBulkImportOptimizer implements BulkImportOptimizer {

    private final CollectionManager collectionManager;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public void enableBulkInsertMode() {
        JdbcTemplate jt = getJdbcTemplate();
        jt.execute("PRAGMA synchronous = OFF");
        jt.execute("PRAGMA journal_mode = MEMORY");
        jt.execute("PRAGMA temp_store = MEMORY");
        // Видалено EXCLUSIVE, щоб уникнути блокування
        jt.execute("PRAGMA cache_size = -500000");
        jt.execute("PRAGMA mmap_size = 2147483648");
        log.debug("PRAGMA встановлено для швидкого імпорту (без EXCLUSIVE)");
    }

    @Override
    public void disableBulkInsertMode() {
        JdbcTemplate jt = getJdbcTemplate();
        jt.execute("PRAGMA synchronous = NORMAL");
        jt.execute("PRAGMA journal_mode = WAL");
        // Примусове завершення будь-яких транзакцій для зняття блокувань
        try {
            jt.execute("COMMIT");
        } catch (Exception ignored) {}
        log.debug("PRAGMA відновлено до стандартних");
    }

    public void dropIndexes() {
        JdbcTemplate jt = getJdbcTemplate();
        try {
            jt.execute("DROP INDEX IF EXISTS idx_books_title");
            jt.execute("DROP INDEX IF EXISTS idx_books_series");
            jt.execute("DROP INDEX IF EXISTS idx_books_language");
            jt.execute("DROP INDEX IF EXISTS idx_authors_last_name");
            jt.execute("DROP INDEX IF EXISTS idx_authors_search_name");
            jt.execute("DROP INDEX IF EXISTS idx_book_authors_book_author");
            jt.execute("DROP INDEX IF EXISTS idx_book_genres_book_genre");
            log.info("Всі індекси видалено");
        } catch (Exception e) {
            log.warn("Помилка при видаленні індексів: {}", e.getMessage());
        }
    }

    public void createIndexes() {
        JdbcTemplate jt = getJdbcTemplate();
        try {
            jt.execute("CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)");
            jt.execute("CREATE INDEX IF NOT EXISTS idx_books_series ON books(series)");
            jt.execute("CREATE INDEX IF NOT EXISTS idx_books_language ON books(language)");
            jt.execute("CREATE INDEX IF NOT EXISTS idx_authors_last_name ON authors(last_name)");
            jt.execute("CREATE INDEX IF NOT EXISTS idx_authors_search_name ON authors(search_name)");
            jt.execute("CREATE INDEX IF NOT EXISTS idx_book_authors_book_author ON book_authors(book_id, author_id)");
            jt.execute("CREATE INDEX IF NOT EXISTS idx_book_genres_book_genre ON book_genres(book_id, genre_code)");
            log.info("Всі індекси створено");
        } catch (Exception e) {
            log.warn("Помилка при створенні індексів: {}", e.getMessage());
        }
    }
}