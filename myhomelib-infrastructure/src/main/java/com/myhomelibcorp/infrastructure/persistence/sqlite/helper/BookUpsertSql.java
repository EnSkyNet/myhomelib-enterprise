package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

/**
 * Canonical SQLite UPSERT contract for the denormalized {@code books} row.
 *
 * <p>Both legacy INPX import and the neutral catalog importer must preserve the same local-state
 * merge rules. Keeping the statement in one place prevents one importer from silently drifting
 * when columns or conflict semantics change.</p>
 */
public final class BookUpsertSql {
    private BookUpsertSql() {
    }

    public static final String UPSERT = """
            INSERT INTO books (
                id, title, series, sequence_number, file_name, folder,
                archive_entry, language, file_size, keywords, annotation,
                rate, progress, update_date, isbn, deleted, local,
                review, created_at, collection_root, year, publisher,
                lib_id, library_rate, translators, city, source_url, format, author_sort
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                series = excluded.series,
                sequence_number = excluded.sequence_number,
                file_name = CASE WHEN books.local = 1 THEN books.file_name ELSE excluded.file_name END,
                folder = CASE WHEN books.local = 1 THEN books.folder ELSE excluded.folder END,
                archive_entry = CASE WHEN books.local = 1 THEN books.archive_entry ELSE excluded.archive_entry END,
                language = excluded.language,
                file_size = CASE WHEN books.local = 1 THEN books.file_size ELSE excluded.file_size END,
                keywords = excluded.keywords,
                annotation = excluded.annotation,
                rate = books.rate,
                progress = books.progress,
                update_date = COALESCE(excluded.update_date, books.update_date),
                isbn = excluded.isbn,
                deleted = excluded.deleted,
                local = CASE WHEN books.local = 1 THEN 1 ELSE excluded.local END,
                review = books.review,
                created_at = books.created_at,
                collection_root = CASE WHEN books.local = 1 THEN books.collection_root ELSE excluded.collection_root END,
                year = excluded.year,
                publisher = excluded.publisher,
                lib_id = CASE WHEN COALESCE(excluded.lib_id, '') <> '' THEN excluded.lib_id ELSE books.lib_id END,
                library_rate = excluded.library_rate,
                translators = excluded.translators,
                city = excluded.city,
                source_url = CASE WHEN COALESCE(excluded.source_url, '') <> '' THEN excluded.source_url ELSE books.source_url END,
                format = CASE WHEN books.local = 1 THEN books.format ELSE excluded.format END,
                author_sort = excluded.author_sort
            """;
}
