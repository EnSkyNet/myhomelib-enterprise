package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteDateTimeCodec;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@Slf4j
public class SqliteReadingProgressRepository implements ReadingProgressRepository {

    private final CollectionManager collectionManager;
    private final SqliteBusyRetryExecutor busyRetry;
    private final TransactionTemplate transactionTemplate;

    /** Compatibility constructor for focused tests that manage their own SQLite transaction boundary. */
    public SqliteReadingProgressRepository(CollectionManager collectionManager,
                                           SqliteBusyRetryExecutor busyRetry) {
        this(collectionManager, busyRetry, null);
    }

    @Autowired
    public SqliteReadingProgressRepository(CollectionManager collectionManager,
                                           SqliteBusyRetryExecutor busyRetry,
                                           @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.collectionManager = java.util.Objects.requireNonNull(collectionManager, "collectionManager");
        this.busyRetry = java.util.Objects.requireNonNull(busyRetry, "busyRetry");
        this.transactionTemplate = transactionTemplate;
    }

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private final RowMapper<ReadingProgressDto> rowMapper = (rs, rowNum) -> {
        ReadingProgressDto dto = ReadingProgressDto.builder()
                .bookId(rs.getString("book_id"))
                .revision(rs.getLong("revision"))
                .anchorId(rs.getString("anchor_id"))
                .textAnchorId(rs.getString("text_anchor_id"))
                .audioAnchorId(rs.getString("audio_anchor_id"))
                .paragraphIndex(rs.getInt("paragraph_index"))
                .paragraphId(rs.getString("paragraph_id"))
                .charOffset(rs.getInt("char_offset"))
                .percent(rs.getDouble("percent"))
                .chapterTitle(rs.getString("chapter_title"))
                .chapterId(rs.getString("chapter_id"))
                .updatedAt(SqliteDateTimeCodec.parse(rs.getString("updated_at")))
                .readingTimeSeconds(rs.getLong("reading_time_seconds"))
                .lastDevice(rs.getString("last_device"))
                .build();

        // Якщо anchor_id порожній, використовуємо paragraph_id як fallback
        if (dto.getAnchorId() == null || dto.getAnchorId().isEmpty()) {
            dto.setAnchorId(dto.getParagraphId());
        }

        return dto;
    };

    @Override
    public void save(ReadingProgressDto progress) {
        // ===== ВИПРАВЛЕНО: ЗАВЖДИ ПЕРЕДАЄМО ЗНАЧЕННЯ ДЛЯ ВСІХ ПОЛІВ =====
        String sql = """
                INSERT INTO reading_progress
                (book_id, anchor_id, text_anchor_id, audio_anchor_id,
                 paragraph_index, paragraph_id, char_offset, percent,
                 chapter_title, chapter_id, updated_at, reading_time_seconds, last_device, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT(book_id) DO UPDATE SET
                    anchor_id=excluded.anchor_id,
                    -- A text update must not erase the independently committed audio anchor,
                    -- and vice versa. This operates inside the same UPSERT as legacy progress.
                    text_anchor_id=COALESCE(excluded.text_anchor_id, reading_progress.text_anchor_id),
                    audio_anchor_id=COALESCE(excluded.audio_anchor_id, reading_progress.audio_anchor_id),
                    paragraph_index=excluded.paragraph_index,
                    paragraph_id=excluded.paragraph_id,
                    char_offset=excluded.char_offset,
                    percent=excluded.percent,
                    chapter_title=excluded.chapter_title,
                    chapter_id=excluded.chapter_id,
                    updated_at=excluded.updated_at,
                    reading_time_seconds=excluded.reading_time_seconds,
                    last_device=excluded.last_device,
                    revision=reading_progress.revision+1
                """;
        // Both guarded variants return 0 rows on conflict. Never fall through to a blind UPSERT.
        // -1 is valid only for a never-deleted row; a previous deletion must
        // invalidate readers that observed absence before the delete/recreate race.
        String insertIfNeverDeleted = """
                INSERT INTO reading_progress
                (book_id, anchor_id, text_anchor_id, audio_anchor_id,
                 paragraph_index, paragraph_id, char_offset, percent,
                 chapter_title, chapter_id, updated_at, reading_time_seconds, last_device, revision)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0
                 WHERE NOT EXISTS (
                     SELECT 1 FROM reading_progress_deleted_generations WHERE book_id = ?
                 )
                ON CONFLICT(book_id) DO NOTHING
                """;
        // A reader that observed a tombstone must present its exact generation.
        // The V73 AFTER INSERT trigger promotes this new row to that revision.
        String insertForObservedDeleteGeneration = """
                INSERT INTO reading_progress
                (book_id, anchor_id, text_anchor_id, audio_anchor_id,
                 paragraph_index, paragraph_id, char_offset, percent,
                 chapter_title, chapter_id, updated_at, reading_time_seconds, last_device, revision)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0
                 WHERE EXISTS (
                     SELECT 1 FROM reading_progress_deleted_generations
                      WHERE book_id = ? AND next_revision = ?
                 )
                ON CONFLICT(book_id) DO NOTHING
                """;
        String updateIfRevision = """
                UPDATE reading_progress SET
                    anchor_id=?,
                    text_anchor_id=COALESCE(?, text_anchor_id),
                    audio_anchor_id=COALESCE(?, audio_anchor_id),
                    paragraph_index=?, paragraph_id=?, char_offset=?, percent=?,
                    chapter_title=?, chapter_id=?, updated_at=?, reading_time_seconds=?,
                    last_device=?, revision=revision+1
                WHERE book_id=? AND revision=?
                """;

        // Normalize again at the persistence boundary. Most callers already sanitize progress,
        // but sync/restore/plugins are not allowed to persist NaN/Infinity or negative positions
        // even if a malformed DTO reaches the repository directly.
        final int paragraphIndex = Math.max(0, progress.getParagraphIndex());
        final int charOffset = Math.max(0, progress.getCharOffset());
        final double percent = Double.isFinite(progress.getPercent())
                ? Math.max(0.0, Math.min(100.0, progress.getPercent()))
                : 0.0;
        final long readingTimeSeconds = Math.max(0L, progress.getReadingTimeSeconds());

        // Значення, які використовуються всередині retry-lambda, мають бути final/effectively final.
        final String anchorId = progress.getAnchorId() != null && !progress.getAnchorId().isEmpty()
                ? progress.getAnchorId()
                : String.valueOf(paragraphIndex);

        // Keep the last-active anchor for legacy/sync clients while preserving each
        // medium in its own SQLite column. Never trust a stale DTO sibling anchor:
        // COALESCE against the committed database row prevents lost updates.
        final boolean audioWrite = anchorId.startsWith("audio:");
        final String textAnchorId = audioWrite ? null : anchorId;
        final String audioAnchorId = audioWrite ? anchorId : null;

        final String paragraphId = progress.getParagraphId() == null || progress.getParagraphId().isEmpty()
                ? anchorId
                : progress.getParagraphId();

        final String chapterTitle = progress.getChapterTitle() == null
                ? ""
                : progress.getChapterTitle();

        final String chapterId = progress.getChapterId() == null
                ? ""
                : progress.getChapterId();

        final String updatedAt = progress.getUpdatedAt() != null
                ? SqliteDateTimeCodec.format(progress.getUpdatedAt())
                : SqliteDateTimeCodec.format(LocalDateTime.now());

        final String lastDevice = progress.getLastDevice() == null || progress.getLastDevice().isBlank()
                ? "desktop"
                : progress.getLastDevice().trim();

        int summaryProgress = (int) Math.round(percent);
        // reading_progress and books.progress form one logical state. Both writes must share
        // one SQLite transaction; otherwise a successful first statement followed by a busy/
        // constraint failure on the second leaves the catalogue projections inconsistent.
        busyRetry.run("reading progress save", () -> {
            Runnable write = () -> {
                JdbcTemplate jdbc = getJdbcTemplate();
                Long observedRevision = progress.getRevision();
                int changed;
                if (observedRevision != null && observedRevision >= 0) {
                    // SQLite evaluates the comparison and mutation in ONE statement.
                    // A second process cannot overwrite a later generation based on stale data.
                    changed = jdbc.update(updateIfRevision,
                            anchorId, textAnchorId, audioAnchorId, paragraphIndex, paragraphId,
                            charOffset, percent, chapterTitle, chapterId, updatedAt,
                            readingTimeSeconds, lastDevice, progress.getBookId(), observedRevision);
                } else if (observedRevision != null && observedRevision == -1L) {
                    changed = jdbc.update(insertIfNeverDeleted,
                            progress.getBookId(), anchorId, textAnchorId, audioAnchorId,
                            paragraphIndex, paragraphId, charOffset, percent, chapterTitle,
                            chapterId, updatedAt, readingTimeSeconds, lastDevice, progress.getBookId());
                } else if (observedRevision != null && observedRevision <= -2L) {
                    // Decode -2 - generation. Math.addExact avoids integer overflow
                    // from malformed inputs such as Long.MIN_VALUE.
                    long generation = Math.negateExact(Math.addExact(observedRevision, 2L));
                    if (generation <= 0) throw new IllegalArgumentException("Invalid delete generation");
                    changed = jdbc.update(insertForObservedDeleteGeneration,
                            progress.getBookId(), anchorId, textAnchorId, audioAnchorId,
                            paragraphIndex, paragraphId, charOffset, percent, chapterTitle,
                            chapterId, updatedAt, readingTimeSeconds, lastDevice, progress.getBookId(), generation);
                } else if (observedRevision == null) {
                    // Legacy/sync path: still increments revision, invalidating any Reader
                    // snapshot observed before this operation. See production risk register.
                    changed = jdbc.update(sql,
                            progress.getBookId(), anchorId, textAnchorId, audioAnchorId,
                            paragraphIndex, paragraphId, charOffset, percent, chapterTitle,
                            chapterId, updatedAt, readingTimeSeconds, lastDevice);
                } else {
                    throw new IllegalArgumentException("Unexpected negative reading progress revision");
                }
                if (changed != 1) {
                    throw new ReadingProgressRevisionConflictException(progress.getBookId());
                }
                // The main table and legacy progress filters read books.progress.
                int summariesUpdated = jdbc.update(
                        "UPDATE books SET progress = ? WHERE id = ?", summaryProgress, progress.getBookId());
                if (summariesUpdated != 1) {
                    // Some restored/legacy SQLite schemas do not enforce foreign keys.
                    // Never commit a reading_progress row without its books projection.
                    throw new IllegalStateException("Reading progress refers to a missing book");
                }
            };
            if (transactionTemplate != null) {
                transactionTemplate.execute(status -> {
                    write.run();
                    return null;
                });
            } else {
                // Focused/unit callers may construct the repository without Spring wiring.
                // Production always receives collectionTransactionTemplate.
                com.myhomelibcorp.infrastructure.persistence.CollectionTransactionExecutor.run(
                        collectionManager, write);
            }
        });

        log.debug("Saved reading progress for book {}: anchor={}, paragraph_id={}, charOffset={}, %={}",
                progress.getBookId(), anchorId, paragraphId, charOffset, percent);
    }

    @Override
    public long revisionWhenAbsent(String bookId) {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("bookId is required");
        var generations = getJdbcTemplate().queryForList(
                "SELECT next_revision FROM reading_progress_deleted_generations WHERE book_id = ?",
                Long.class, bookId);
        if (generations.isEmpty()) return -1L;
        long generation = generations.getFirst();
        if (generation < 1 || generation > Long.MAX_VALUE - 2)
            throw new IllegalStateException("Unsupported progress delete generation");
        return -2L - generation;
    }

    @Override
    public Optional<ReadingProgressDto> findByBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM reading_progress WHERE book_id = ? LIMIT 1";
        return getJdbcTemplate().query(sql, rowMapper, bookId).stream().findFirst();
    }

    @Override
    public Map<String, ReadingProgressDto> findByBookIds(Collection<String> bookIds) {
        Map<String, ReadingProgressDto> result = new LinkedHashMap<>();
        if (bookIds == null || bookIds.isEmpty()) return result;
        var ids = bookIds.stream().filter(java.util.Objects::nonNull).filter(id -> !id.isBlank()).distinct().toList();
        SqliteInClauseSupport.forEachChunk(ids, part -> {
            String sql = "SELECT * FROM reading_progress WHERE book_id IN (" + SqliteInClauseSupport.placeholders(part.size()) + ")";
            for (ReadingProgressDto value : getJdbcTemplate().query(sql, rowMapper, part.toArray())) result.put(value.getBookId(), value);
        });
        return result;
    }

    @Override
    public void deleteByBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) return;
        // A sync tombstone must reset both the detailed progress row and the summary
        // used by shelves/filters. A one-statement delete left books.progress stale.
        // Keep these changes in the SAME transaction, including the fallback path
        // used by focused callers without Spring's configured transaction template.
        busyRetry.run("reading progress delete", () -> {
            Runnable remove = () -> {
                JdbcTemplate jdbc = getJdbcTemplate();
                jdbc.update("DELETE FROM reading_progress WHERE book_id = ?", bookId);
                jdbc.update("UPDATE books SET progress = 0 WHERE id = ?", bookId);
            };
            if (transactionTemplate != null) {
                transactionTemplate.execute(status -> {
                    remove.run();
                    return null;
                });
            } else {
                com.myhomelibcorp.infrastructure.persistence.CollectionTransactionExecutor.run(
                        collectionManager, remove);
            }
        });
        log.debug("Deleted reading progress for book: {}", bookId);
    }

}