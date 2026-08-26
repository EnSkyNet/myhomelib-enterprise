package com.myhomelibcorp.infrastructure.persistence.sqlite.query;

public final class BookQueries {

    private BookQueries() {}

    // ==================== SELECT ====================
    public static final String FIND_ALL = "SELECT * FROM books LIMIT ? OFFSET ?";
    public static final String FIND_BY_ID = "SELECT * FROM books WHERE id = ?";
    public static final String FIND_BY_IDS = "SELECT * FROM books WHERE id IN (%s)";
    public static final String FIND_BY_TITLE_AND_AUTHOR = """
            SELECT b.* FROM books b
            JOIN book_authors ba ON b.id = ba.book_id
            JOIN authors a ON ba.author_id = a.id
            WHERE b.title = ? AND a.last_name = ?
            LIMIT 1
            """;
    public static final String COUNT_ALL = "SELECT COUNT(*) FROM books";
    public static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM books WHERE id = ?";
    public static final String COUNT_BY_AUTHOR = """
            SELECT COUNT(*) FROM books b
            JOIN book_authors ba ON b.id = ba.book_id
            WHERE ba.author_id = ?
            """;

    // ==================== INSERT / UPDATE (з collection_root) ====================
    public static final String INSERT_OR_UPDATE_BOOK = """
            INSERT INTO books (
                id, title, series, sequence_number, file_name, folder,
                archive_entry, language, file_size, keywords, annotation,
                rate, progress, update_date, isbn, deleted, local,
                review, created_at, collection_root, year, publisher, lib_id, library_rate, translators, city, source_url,
                format, author_sort
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                series = excluded.series,
                sequence_number = excluded.sequence_number,
                file_name = excluded.file_name,
                folder = excluded.folder,
                archive_entry = excluded.archive_entry,
                language = excluded.language,
                file_size = excluded.file_size,
                keywords = excluded.keywords,
                annotation = excluded.annotation,
                rate = excluded.rate,
                progress = excluded.progress,
                update_date = excluded.update_date,
                isbn = excluded.isbn,
                deleted = excluded.deleted,
                local = excluded.local,
                review = excluded.review,
                created_at = excluded.created_at,
                collection_root = excluded.collection_root,
                year = excluded.year,
                publisher = excluded.publisher,
                lib_id = excluded.lib_id,
                library_rate = excluded.library_rate,
                translators = excluded.translators,
                city = excluded.city,
                source_url = excluded.source_url,
                format = excluded.format,
                author_sort = excluded.author_sort
            """;

    public static final String DELETE_BY_ID = "DELETE FROM books WHERE id = ?";
    public static final String UPDATE_RATE = "UPDATE books SET rate = ?, update_date = CURRENT_TIMESTAMP WHERE id = ?";
    public static final String UPDATE_PROGRESS = "UPDATE books SET progress = ?, update_date = CURRENT_TIMESTAMP WHERE id = ?";
    public static final String UPDATE_BOOK = """
            UPDATE books SET
                title = ?,
                series = ?,
                sequence_number = ?,
                file_name = ?,
                folder = ?,
                archive_entry = ?,
                language = ?,
                file_size = ?,
                keywords = ?,
                annotation = ?,
                rate = ?,
                progress = ?,
                update_date = ?,
                isbn = ?,
                deleted = ?,
                local = ?,
                review = ?,
                created_at = ?,
                collection_root = ?,
                year = ?,
                publisher = ?,
                lib_id = ?,
                library_rate = ?,
                translators = ?,
                city = ?,
                source_url = ?,
                format = ?,
                author_sort = ?
            WHERE id = ?
            """;
}