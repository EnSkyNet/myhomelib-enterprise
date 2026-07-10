package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class JdbcBatchWriter {

    private final CollectionManager collectionManager;
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    /**
     * Вставляє батч книг з колонкою collection_root.
     */
    public void batchInsertFull(List<Object[]> booksData,
                                Map<String, String> authorCache,
                                Map<String, String> genreCache) {
        if (booksData.isEmpty()) return;

        JdbcTemplate jt = getJdbcTemplate();

        // SQL з collection_root
        String insertBookSql = """
            INSERT INTO books (
                id, title, series, sequence_number, file_name, folder,
                archive_entry, language, file_size, keywords, annotation,
                rate, progress, update_date, isbn, deleted, local,
                review, created_at, collection_root
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                collection_root = excluded.collection_root
            """;

        List<Object[]> bookBatch = new ArrayList<>();
        List<Object[]> authorLinkBatch = new ArrayList<>();
        List<Object[]> genreLinkBatch = new ArrayList<>();

        for (Object[] row : booksData) {
            // row: [0..18] book fields, [19] authorIds, [20] genreCodes, [21] collectionRoot
            Object[] bookRow = new Object[20];
            System.arraycopy(row, 0, bookRow, 0, 19);
            bookRow[19] = row[21]; // collection_root
            bookBatch.add(bookRow);

            String bookId = (String) row[0];
            String authorIds = (String) row[19];
            if (authorIds != null && !authorIds.isBlank()) {
                for (String id : authorIds.split(",")) {
                    authorLinkBatch.add(new Object[]{bookId, id.trim()});
                }
            }
            String genreCodes = (String) row[20];
            if (genreCodes != null && !genreCodes.isBlank()) {
                for (String code : genreCodes.split(",")) {
                    genreLinkBatch.add(new Object[]{bookId, code.trim()});
                }
            }
        }

        jt.batchUpdate(insertBookSql, bookBatch, 1000, (ps, row) -> {
            int idx = 1;
            ps.setString(idx++, (String) row[0]);
            ps.setString(idx++, (String) row[1]);
            ps.setString(idx++, (String) row[2]);
            ps.setInt(idx++, (Integer) row[3]);
            ps.setString(idx++, (String) row[4]);
            ps.setString(idx++, (String) row[5]);
            ps.setString(idx++, (String) row[6]);
            ps.setString(idx++, (String) row[7]);
            ps.setLong(idx++, (Long) row[8]);
            ps.setString(idx++, (String) row[9]);
            ps.setString(idx++, (String) row[10]);
            ps.setInt(idx++, (Integer) row[11]);
            ps.setInt(idx++, (Integer) row[12]);
            ps.setString(idx++, (String) row[13]);
            ps.setString(idx++, (String) row[14]);
            ps.setInt(idx++, (Integer) row[15]);
            ps.setInt(idx++, (Integer) row[16]);
            ps.setString(idx++, (String) row[17]);
            ps.setString(idx++, (String) row[18]);
            ps.setString(idx++, (String) row[19]); // collection_root
        });

        if (!authorLinkBatch.isEmpty()) {
            jt.batchUpdate("INSERT OR IGNORE INTO book_authors (book_id, author_id) VALUES (?, ?)",
                    authorLinkBatch, 1000, (ps, row) -> {
                        ps.setString(1, (String) row[0]);
                        ps.setString(2, (String) row[1]);
                    });
        }
        if (!genreLinkBatch.isEmpty()) {
            jt.batchUpdate("INSERT OR IGNORE INTO book_genres (book_id, genre_code) VALUES (?, ?)",
                    genreLinkBatch, 1000, (ps, row) -> {
                        ps.setString(1, (String) row[0]);
                        ps.setString(2, (String) row[1]);
                    });
        }

        log.debug("Batch inserted {} books", bookBatch.size());
    }
}