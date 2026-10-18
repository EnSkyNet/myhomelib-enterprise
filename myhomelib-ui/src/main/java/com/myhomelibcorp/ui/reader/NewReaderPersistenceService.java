package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.audio.AudioPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Slf4j
public class NewReaderPersistenceService {

    private final ReadingProgressRepository readingProgressRepository;
    private final BookmarkRepository bookmarkRepository;
    private final com.myhomelibcorp.ui.service.LocalizationService i18n;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;
    private final ReaderPositionRecoveryJournal recoveryJournal;
    private final ReaderAudioRecoveryJournal audioRecoveryJournal;
    private final ReaderProgressTransactionGuard progressTransactionGuard = new ReaderProgressTransactionGuard();
    // Bound the number of per-book synchronization objects. A global synchronized
    // savePosition blocked unrelated books and regressed concurrent Reader closes.
    private static final Object[] POSITION_LOCKS = java.util.stream.IntStream.range(0, 256)
            .mapToObj(index -> new Object()).toArray(Object[]::new);

    private static Object positionLock(String collectionId, String bookId) {
        return POSITION_LOCKS[Math.floorMod(java.util.Objects.hash(collectionId, bookId), POSITION_LOCKS.length)];
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NewReaderPersistenceService(ReadingProgressRepository readingProgressRepository,
            BookmarkRepository bookmarkRepository,
            com.myhomelibcorp.ui.service.LocalizationService i18n,
            CollectionLifecyclePort collectionLifecyclePort,
            LibraryOperationCoordinator operationCoordinator,
            ReaderPositionRecoveryJournal recoveryJournal,
            ReaderAudioRecoveryJournal audioRecoveryJournal) {
        this.readingProgressRepository = readingProgressRepository;
        this.bookmarkRepository = bookmarkRepository;
        this.i18n = i18n;
        this.collectionLifecyclePort = collectionLifecyclePort;
        this.operationCoordinator = operationCoordinator;
        this.recoveryJournal = recoveryJournal;
        this.audioRecoveryJournal = audioRecoveryJournal;
    }

    // Existing focused tests supply a custom text journal; keep a separate audio
    // namespace so text and audiobook records can never overwrite each other.
    NewReaderPersistenceService(ReadingProgressRepository readingProgressRepository,
            BookmarkRepository bookmarkRepository,
            com.myhomelibcorp.ui.service.LocalizationService i18n,
            CollectionLifecyclePort collectionLifecyclePort,
            LibraryOperationCoordinator operationCoordinator,
            ReaderPositionRecoveryJournal recoveryJournal) {
        this(readingProgressRepository, bookmarkRepository, i18n, collectionLifecyclePort,
                operationCoordinator, recoveryJournal,
                new ReaderAudioRecoveryJournal(java.nio.file.Path.of(
                        System.getProperty("java.io.tmpdir"), "myhomelib-audio-recovery-tests",
                        java.util.UUID.randomUUID().toString())));
    }

    // Package-private compatibility constructor for focused unit tests. Production
    // injection always uses the six-argument @Autowired constructor above.
    NewReaderPersistenceService(ReadingProgressRepository readingProgressRepository,
            BookmarkRepository bookmarkRepository,
            com.myhomelibcorp.ui.service.LocalizationService i18n,
            CollectionLifecyclePort collectionLifecyclePort,
            LibraryOperationCoordinator operationCoordinator) {
        this(readingProgressRepository, bookmarkRepository, i18n, collectionLifecyclePort,
                operationCoordinator, new ReaderPositionRecoveryJournal(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "myhomelib-reader-tests",
                                java.util.UUID.randomUUID().toString())));
    }

    // Cache only recently saved positions. Every entry here has already been persisted to
    // SQLite; evicting it can cause an extra read/write later, but never loses a position.
    // An unbounded map would retain a position and collection/book IDs for every opened book.
    private static final int MAX_SAVED_POSITION_CACHE = 512;
    private final Map<String, ReaderPosition> lastSavedPositions = Collections.synchronizedMap(
            new LinkedHashMap<String, ReaderPosition>(MAX_SAVED_POSITION_CACHE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ReaderPosition> eldest) {
                    return size() > MAX_SAVED_POSITION_CACHE;
                }
            });

    // ==================== ПОЗИЦІЯ ====================


    /** Зберігає позицію читання, якщо вона змінилася. */
    public boolean savePosition(String bookId, ReaderPosition position, long totalTextLength) {
        return savePosition(activeCollectionId(), bookId, position, totalTextLength);
    }

    /** Collection-stable variant used by autosavers and long-lived Reader workspaces. */
    public boolean savePosition(String collectionId, String bookId, ReaderPosition position, long totalTextLength) {
        if (bookId == null || position == null) return true;
        synchronized (positionLock(collectionId, bookId)) {
        try {
            return progressTransactionGuard.withLock(collectionId, bookId, () -> withReaderState(collectionId, () -> {
                Optional<ReadingProgressDto> existing = readingProgressRepository.findByBookId(bookId);
                // A cached text position is NOT evidence of the current SQLite
                // anchor: audio playback, another session or recovery may have
                // changed the same row. Verify the persisted anchor before skipping.
                if (existing.isPresent() && position.serialize().equals(textAnchor(existing.get()))) {
                    // A failed older save may still be waiting in a journal. If the
                    // user deliberately returns to the committed position, do not
                    // skip that choice and later replay the obsolete failed anchor.
                    try {
                        Optional<ReaderPositionRecoveryJournal.Pending> pending =
                                recoveryJournal.load(collectionId, bookId);
                        if (pending.isEmpty() || position.equals(pending.get().position())) {
                            if (pending.isPresent()) recoveryJournal.acknowledgeIfMatches(collectionId, bookId, pending.get());
                            lastSavedPositions.put(cacheKey(collectionId, bookId), position);
                            return true;
                        }
                    } catch (java.io.IOException unsafeJournal) {
                        throw new java.io.UncheckedIOException(unsafeJournal);
                    }
                }
                // Capture the original anchor BEFORE mutating the DTO returned by the
                // repository; otherwise crash recovery would compare against the new
                // value and fail to recognize an interrupted SQLite write.
                String originalAnchor = existing.map(NewReaderPersistenceService::textAnchor).orElse(null);
                ReadingProgressDto dto;
                if (existing.isPresent()) {
                    dto = copyProgress(existing.get());
                    dto.setAnchorId(position.serialize());
                    dto.setTextAnchorId(position.serialize());
                    dto.setParagraphIndex(position.paragraphIndex());
                    dto.setParagraphId("p" + position.paragraphIndex());
                    dto.setCharOffset(position.charOffset());
                    dto.setPercent(position.getPercent(totalTextLength));
                    dto.setUpdatedAt(LocalDateTime.now());
                    dto.setLastDevice("desktop");
                } else {
                    dto = ReadingProgressDto.builder()
                            .bookId(bookId)
                            .revision(readingProgressRepository.revisionWhenAbsent(bookId))
                            .anchorId(position.serialize())
                            .textAnchorId(position.serialize())
                            .paragraphIndex(position.paragraphIndex())
                            .paragraphId("p" + position.paragraphIndex())
                            .charOffset(position.charOffset())
                            .percent(position.getPercent(totalTextLength))
                            .updatedAt(LocalDateTime.now())
                            .readingTimeSeconds(0)
                            .lastDevice("desktop")
                            .build();
                }
                // Write-ahead evidence survives a process crash or an unavailable database.
                // If evidence cannot be written, do not pretend the save is crash-safe.
                ReaderPositionRecoveryJournal.Pending prepared;
                try {
                    prepared = recoveryJournal.prepare(collectionId, bookId, originalAnchor, position, totalTextLength,
                            dto.getRevision());
                } catch (java.io.IOException journalingFailure) {
                    throw new java.io.UncheckedIOException(journalingFailure);
                }
                readingProgressRepository.save(dto);
                lastSavedPositions.put(cacheKey(collectionId, bookId), position);
                try {
                    recoveryJournal.acknowledgeIfMatches(collectionId, bookId, prepared);
                } catch (java.io.IOException pendingCleanup) {
                    // SQLite has committed. The next load will recognize the matching
                    // position and clear the obsolete recovery record.
                    log.warn("Reader saved but recovery evidence cleanup was deferred: {}", com.myhomelibcorp.ui.util.UiExceptionSupport.technicalMessage(pendingCleanup));
                }
                return true;
            }));
        } catch (Exception e) {
            log.warn("Reader position was not saved for collection={} book={}: {}", collectionId, bookId, com.myhomelibcorp.ui.util.UiExceptionSupport.technicalMessage(e));
            return false;
        }
        }
    }

    /** Завантажує позицію читання з БД. */
    public Optional<ReaderPosition> loadPosition(String bookId) {
        return loadPosition(activeCollectionId(), bookId);
    }

    public Optional<ReaderPosition> loadPosition(String collectionId, String bookId) {
        if (bookId == null) return Optional.empty();
        synchronized (positionLock(collectionId, bookId)) {
        try {
            return progressTransactionGuard.withLock(collectionId, bookId, () -> withReaderState(collectionId, () -> {
                Optional<ReadingProgressDto> dto = readingProgressRepository.findByBookId(bookId);
                Optional<ReaderPositionRecoveryJournal.Pending> pending;
                try {
                    pending = recoveryJournal.load(collectionId, bookId);
                } catch (java.io.IOException unsafeJournal) {
                    // A truncated/corrupt recovery file must not prevent opening a
                    // valid book. Preserve the evidence, and use the SQLite value.
                    log.warn("Reader recovery record could not be verified for collection={} book={}: {}",
                            collectionId, bookId, com.myhomelibcorp.ui.util.UiExceptionSupport.technicalMessage(unsafeJournal));
                    pending = Optional.empty();
                }
                if (pending.isPresent()) {
                    var recovery = pending.get();
                    String currentAnchor = dto.map(NewReaderPersistenceService::textAnchor).orElse(null);
                    if (recovery.position().serialize().equals(currentAnchor)) {
                        // Crash after SQLite commit, before journal acknowledgement.
                        acknowledgeRecoveryBestEffort(collectionId, bookId, recovery);
                    } else if (java.util.Objects.equals(recovery.originalAnchor(), currentAnchor)
                            && recoveryRevisionMatches(recovery.expectedRevision(),
                                    dto.isPresent() ? dto.get().getRevision()
                                            : Long.valueOf(readingProgressRepository.revisionWhenAbsent(bookId)))) {
                        // Only replay if SQLite still holds exactly the position from
                        // before the interrupted write; never overwrite an unrelated edit.
                        ReadingProgressDto recovered = dto.map(NewReaderPersistenceService::copyProgress)
                                .orElseGet(() -> ReadingProgressDto.builder()
                                        .bookId(bookId).revision(readingProgressRepository.revisionWhenAbsent(bookId)).readingTimeSeconds(0).build());
                        recovered.setAnchorId(recovery.position().serialize());
                        recovered.setTextAnchorId(recovery.position().serialize());
                        recovered.setParagraphIndex(recovery.position().paragraphIndex());
                        recovered.setParagraphId("p" + recovery.position().paragraphIndex());
                        recovered.setCharOffset(recovery.position().charOffset());
                        recovered.setPercent(recovery.position().getPercent(recovery.totalLength()));
                        recovered.setUpdatedAt(LocalDateTime.now());
                        recovered.setLastDevice("desktop");
                        readingProgressRepository.save(recovered);
                        acknowledgeRecoveryBestEffort(collectionId, bookId, recovery);
                        lastSavedPositions.put(cacheKey(collectionId, bookId), recovery.position());
                        return Optional.of(recovery.position());
                    } else {
                        log.warn("Reader recovery needs manual review: SQLite position changed for collection={} book={}",
                                collectionId, bookId);
                        // Preserve evidence; prefer the current database value.
                    }
                }
                if (dto.isPresent()) {
                    // The same SQLite progress row can contain an audiobook anchor.
                    // Never report an audio anchor (or corrupted text anchor) as
                    // text position 0:0:0:0 due to ReaderPosition.parse fallback.
                    String anchor = textAnchor(dto.get());
                    if (!isValidTextAnchor(anchor)) return Optional.empty();
                    ReaderPosition position = ReaderPosition.parse(anchor);
                    lastSavedPositions.put(cacheKey(collectionId, bookId), position);
                    return Optional.of(position);
                }
                return Optional.empty();
            }));
        } catch (Exception e) {
            throw new IllegalStateException(i18n.text("ui.reader.persistence.position_load_error"), e);
        }
        }
    }

    private static boolean isValidTextAnchor(String anchor) {
        if (anchor == null || anchor.isBlank()) return false;
        if (!anchor.matches("(?:[0-9]+|[0-9]+:[0-9]+:[0-9]+:[0-9]+)")) return false;
        ReaderPosition position = ReaderPosition.parse(anchor);
        return position.isValid(Long.MAX_VALUE) && ("0".equals(anchor)
                || "0:0:0:0".equals(anchor) || !position.isStart());
    }

    /** A legacy v1 journal has no generation proof: do not replay it onto a
     * versioned SQLite row even if the human-readable anchor happens to match.
     * The evidence stays on disk for manual review rather than being deleted. */
    private static boolean recoveryRevisionMatches(Long journalRevision, Long currentRevision) {
        return journalRevision == null ? currentRevision == null
                : journalRevision.equals(currentRevision);
    }

    private void acknowledgeRecoveryBestEffort(String collectionId, String bookId,
            ReaderPositionRecoveryJournal.Pending expected) {
        try {
            recoveryJournal.acknowledgeIfMatches(collectionId, bookId, expected);
        } catch (java.io.IOException cleanupFailure) {
            log.warn("Reader recovery record cleanup was deferred for collection={} book={}: {}",
                    collectionId, bookId, com.myhomelibcorp.ui.util.UiExceptionSupport.technicalMessage(cleanupFailure));
        }
    }

    /**
     * Очищує кеш позицій (при зміні колекції тощо).
     */
    public void clearCache() {
        lastSavedPositions.clear();
        log.debug("🧹 Кеш позицій очищено");
    }


    // ==================== AUDIOBOOK POSITION ====================

    public boolean saveAudioPosition(String bookId, AudioPosition position, double progressPercent) {
        return saveAudioPosition(activeCollectionId(), bookId, position, progressPercent);
    }

    public boolean saveAudioPosition(String collectionId, String bookId, AudioPosition position, double progressPercent) {
        if (bookId == null || position == null) return true;
        synchronized (positionLock(collectionId, bookId)) {
            try {
                return progressTransactionGuard.withLock(collectionId, bookId, () -> withReaderState(collectionId, () -> {
                    Optional<ReadingProgressDto> existing = readingProgressRepository.findByBookId(bookId);
                    String previous = existing.map(NewReaderPersistenceService::audioAnchor).orElse(null);
                    double percent = finitePercent(progressPercent);
                    // The durable journal must exist before any repository object is
                    // mutated. Preserve prior progress if persistence fails.
                    ReaderAudioRecoveryJournal.Pending prepared;
                    try {
                        prepared = audioRecoveryJournal.prepare(collectionId, bookId, previous, position, percent,
                                existing.isPresent() ? existing.get().getRevision()
                                        : Long.valueOf(readingProgressRepository.revisionWhenAbsent(bookId)));
                    } catch (java.io.IOException journalFailure) {
                        throw new java.io.UncheckedIOException(journalFailure);
                    }
                    ReadingProgressDto dto = existing.map(NewReaderPersistenceService::copyProgress)
                            .orElseGet(() -> ReadingProgressDto.builder()
                                    .bookId(bookId).revision(readingProgressRepository.revisionWhenAbsent(bookId)).readingTimeSeconds(0).build());
                    dto.setAnchorId(position.serialize());
                    dto.setAudioAnchorId(position.serialize());
                    dto.setParagraphIndex(position.chapterIndex());
                    dto.setParagraphId("audio-track:" + position.trackIndex());
                    dto.setCharOffset(0);
                    dto.setPercent(percent);
                    dto.setUpdatedAt(LocalDateTime.now());
                    dto.setLastDevice("desktop");
                    readingProgressRepository.save(dto);
                    lastSavedPositions.remove(cacheKey(collectionId, bookId));
                    acknowledgeAudioRecoveryBestEffort(collectionId, bookId, prepared);
                    return true;
                }));
            } catch (Exception e) {
                log.warn("Audiobook position was not saved for collection={} book={}: {}",
                        collectionId, bookId, com.myhomelibcorp.ui.util.UiExceptionSupport.technicalMessage(e));
                return false;
            }
        }
    }

    public Optional<AudioPosition> loadAudioPosition(String bookId) {
        return loadAudioPosition(activeCollectionId(), bookId);
    }

    public Optional<AudioPosition> loadAudioPosition(String collectionId, String bookId) {
        if (bookId == null) return Optional.empty();
        synchronized (positionLock(collectionId, bookId)) {
            try {
                return progressTransactionGuard.withLock(collectionId, bookId, () -> withReaderState(collectionId, () -> {
                    Optional<ReadingProgressDto> existing = readingProgressRepository.findByBookId(bookId);
                    Optional<ReaderAudioRecoveryJournal.Pending> pending;
                    try {
                        pending = audioRecoveryJournal.load(collectionId, bookId);
                    } catch (java.io.IOException invalid) {
                        // Keep damaged evidence for manual review. A valid SQLite
                        // anchor remains readable even if journal verification fails.
                        log.warn("Audiobook recovery record rejected for collection={} book={}: {}",
                                collectionId, bookId,
                                com.myhomelibcorp.ui.util.UiExceptionSupport.technicalMessage(invalid));
                        pending = Optional.empty();
                    }
                    if (pending.isPresent()) {
                        var recovery = pending.get();
                        String current = existing.map(NewReaderPersistenceService::audioAnchor).orElse(null);
                        if (recovery.position().serialize().equals(current)) {
                            acknowledgeAudioRecoveryBestEffort(collectionId, bookId, recovery);
                        } else if (java.util.Objects.equals(recovery.originalAnchor(), current)
                                && recoveryRevisionMatches(recovery.expectedRevision(),
                                        existing.isPresent() ? existing.get().getRevision()
                                                : Long.valueOf(readingProgressRepository.revisionWhenAbsent(bookId)))) {
                            ReadingProgressDto dto = existing.map(NewReaderPersistenceService::copyProgress)
                                    .orElseGet(() -> ReadingProgressDto.builder()
                                            .bookId(bookId).revision(readingProgressRepository.revisionWhenAbsent(bookId)).readingTimeSeconds(0).build());
                            dto.setAnchorId(recovery.position().serialize());
                            dto.setAudioAnchorId(recovery.position().serialize());
                            dto.setParagraphIndex(recovery.position().chapterIndex());
                            dto.setParagraphId("audio-track:" + recovery.position().trackIndex());
                            dto.setCharOffset(0);
                            dto.setPercent(recovery.percent());
                            dto.setUpdatedAt(LocalDateTime.now());
                            dto.setLastDevice("desktop");
                            readingProgressRepository.save(dto);
                            lastSavedPositions.remove(cacheKey(collectionId, bookId));
                            acknowledgeAudioRecoveryBestEffort(collectionId, bookId, recovery);
                            return Optional.of(recovery.position());
                        } else {
                            // Never overwrite progress written by another session/device.
                            log.warn("Audiobook recovery conflicts with changed SQLite position for collection={} book={}",
                                    collectionId, bookId);
                        }
                    }
                    return existing.flatMap(dto -> AudioPosition.parse(audioAnchor(dto)));
                }));
            } catch (Exception e) {
                throw new IllegalStateException(i18n.text("ui.reader.persistence.position_load_error"), e);
            }
        }
    }

    private void acknowledgeAudioRecoveryBestEffort(String collectionId, String bookId,
            ReaderAudioRecoveryJournal.Pending expected) {
        try {
            audioRecoveryJournal.acknowledgeIfMatches(collectionId, bookId, expected);
        } catch (java.io.IOException cleanup) {
            log.warn("Audiobook position saved; recovery evidence retained for later cleanup: {}",
                    com.myhomelibcorp.ui.util.UiExceptionSupport.technicalMessage(cleanup));
        }
    }

    /** The active legacy anchor is only a fallback for records not yet populated by V71. */
    private static String textAnchor(ReadingProgressDto dto) {
        if (dto.getTextAnchorId() != null && !dto.getTextAnchorId().isBlank()) return dto.getTextAnchorId();
        String legacy = dto.getAnchorId();
        return isValidTextAnchor(legacy) ? legacy : null;
    }

    private static String audioAnchor(ReadingProgressDto dto) {
        if (dto.getAudioAnchorId() != null && !dto.getAudioAnchorId().isBlank()) return dto.getAudioAnchorId();
        String legacy = dto.getAnchorId();
        return AudioPosition.parse(legacy).isPresent() ? legacy : null;
    }

    // Do not mutate repository-returned DTO instances before SQLite commits.
    private static ReadingProgressDto copyProgress(ReadingProgressDto original) {
        return ReadingProgressDto.builder()
                .bookId(original.getBookId())
                .revision(original.getRevision())
                .anchorId(original.getAnchorId())
                .textAnchorId(original.getTextAnchorId())
                .audioAnchorId(original.getAudioAnchorId())
                .paragraphIndex(original.getParagraphIndex())
                .paragraphId(original.getParagraphId())
                .charOffset(original.getCharOffset())
                .percent(original.getPercent())
                .chapterTitle(original.getChapterTitle())
                .chapterId(original.getChapterId())
                .updatedAt(original.getUpdatedAt())
                .readingTimeSeconds(original.getReadingTimeSeconds())
                .lastDevice(original.getLastDevice())
                .build();
    }

    public Bookmark saveAudioBookmark(String bookId, AudioPosition position, double percent, String title, String context) {
        return saveAudioBookmark(activeCollectionId(), bookId, position, percent, title, context);
    }

    public Bookmark saveAudioBookmark(String collectionId, String bookId, AudioPosition position, double percent, String title, String context) {
        if (bookId == null || position == null) throw new IllegalArgumentException("bookId and position are required");
        try {
            return withReaderState(collectionId, () -> {
                Bookmark bookmark = Bookmark.builder()
                        .id(UUID.randomUUID().toString())
                        .bookId(bookId)
                        .paragraphId(position.serialize())
                        .charOffset(0)
                        .position(finitePercent(percent))
                        .chapterTitle(title != null ? title : i18n.text("ui.reader.bookmark.default_title"))
                        .context(context != null ? context : "")
                        .createdAt(LocalDateTime.now())
                        .build();
                return bookmarkRepository.save(bookmark);
            });
        } catch (Exception e) {
            throw new IllegalStateException(i18n.text("ui.reader.persistence.bookmark_save_error"), e);
        }
    }

    public Optional<AudioPosition> audioBookmarkToPosition(Bookmark bookmark) {
        return bookmark == null ? Optional.empty() : AudioPosition.parse(bookmark.getParagraphId());
    }

    public ReaderBookmarkItem saveAudioBookmarkItem(
            String collectionId, String bookId, AudioPosition position, double percent, String title, String context) {
        return toReaderBookmarkItem(saveAudioBookmark(collectionId, bookId, position, percent, title, context));
    }

    public Optional<AudioPosition> audioBookmarkToPosition(ReaderBookmarkItem bookmark) {
        return bookmark == null ? Optional.empty() : AudioPosition.parse(bookmark.paragraphId());
    }

    // ==================== ЗАКЛАДКИ ====================

    public Bookmark saveBookmark(String bookId, ReaderPosition position, long totalTextLength, String title, String context) {
        return saveBookmark(activeCollectionId(), bookId, position, totalTextLength, title, context);
    }

    public Bookmark saveBookmark(String collectionId, String bookId, ReaderPosition position, long totalTextLength, String title, String context) {
        if (bookId == null || position == null) {
            throw new IllegalArgumentException("bookId and position are required");
        }

        try {
            return withReaderState(collectionId, () -> {
                String paragraphId = "rp:" + position.serialize();
                double posPercent = position.getPercent(totalTextLength);

                Bookmark bookmark = Bookmark.builder()
                        .id(UUID.randomUUID().toString())
                        .bookId(bookId)
                        .paragraphId(paragraphId)
                        .charOffset(position.charOffset())
                        .position(posPercent)
                        .chapterTitle(title != null ? title : i18n.format("ui.reader.persistence.chapter_fallback", position.chapterIndex() + 1))
                        .context(context != null ? context : "")
                        .createdAt(LocalDateTime.now())
                        .build();

                Bookmark saved = bookmarkRepository.save(bookmark);
                log.info("⭐ Закладку збережено в БД: collection={}, book={}, id={}", collectionId, bookId, saved.getId());
                return saved;
            });
        } catch (Exception e) {
            throw new IllegalStateException(i18n.text("ui.reader.persistence.bookmark_save_error"), e);
        }
    }

    public List<Bookmark> loadBookmarks(String bookId) {
        return loadBookmarks(activeCollectionId(), bookId);
    }

    /** Collection-stable variant for async Reader flows. */
    public List<Bookmark> loadBookmarks(String collectionId, String bookId) {
        if (bookId == null) {
            return List.of();
        }
        try {
            return withReaderState(collectionId, () -> bookmarkRepository.findByBookId(bookId));
        } catch (Exception e) {
            throw new IllegalStateException(i18n.text("ui.reader.persistence.bookmarks_load_error"), e);
        }
    }

    public ReaderBookmarkItem saveBookmarkItem(
            String collectionId, String bookId, ReaderPosition position, long totalTextLength, String title, String context) {
        return toReaderBookmarkItem(saveBookmark(collectionId, bookId, position, totalTextLength, title, context));
    }

    public List<ReaderBookmarkItem> loadBookmarkItems(String collectionId, String bookId) {
        return loadBookmarks(collectionId, bookId).stream().map(NewReaderPersistenceService::toReaderBookmarkItem).toList();
    }

    public void deleteBookmark(String bookmarkId) {
        if (bookmarkId == null) return;
        String collectionId = activeCollectionId();
        withReaderState(collectionId, () -> {
            bookmarkRepository.deleteById(bookmarkId);
            return null;
        });
    }

    public void deleteBookmark(String collectionId, String bookId, String bookmarkId) {
        if (bookmarkId == null || bookId == null || bookId.isBlank()) return;
        try {
            withReaderState(collectionId, () -> {
                boolean belongsToBook = bookmarkRepository.findByBookId(bookId).stream()
                        .anyMatch(bookmark -> bookmarkId.equals(bookmark.getId()));
                if (!belongsToBook) {
                    throw new IllegalStateException("Bookmark does not belong to the expected book");
                }
                bookmarkRepository.deleteById(bookmarkId);
                log.debug("🗑️ Закладку видалено з БД: collection={}, book={}, id={}", collectionId, bookId, bookmarkId);
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException(i18n.text("ui.reader.persistence.bookmark_delete_error"), e);
        }
    }


    public int getBookmarkCount(String bookId) {
        return getBookmarkCount(activeCollectionId(), bookId);
    }

    public int getBookmarkCount(String collectionId, String bookId) {
        if (bookId == null) {
            return 0;
        }

        try {
            return withReaderState(collectionId, () -> bookmarkRepository.countByBookId(bookId));
        } catch (Exception e) {
            throw new IllegalStateException(i18n.text("ui.reader.persistence.bookmark_count_error"), e);
        }
    }

    public ReaderPosition bookmarkToPosition(Bookmark bookmark, long totalTextLength) {
        if (bookmark == null) return ReaderPosition.start();
        return bookmarkPosition(bookmark.getParagraphId(), bookmark.getPosition(), bookmark.getCharOffset(), totalTextLength);
    }

    public ReaderPosition bookmarkToPosition(ReaderBookmarkItem bookmark, long totalTextLength) {
        if (bookmark == null) return ReaderPosition.start();
        return bookmarkPosition(bookmark.paragraphId(), bookmark.position(), bookmark.charOffset(), totalTextLength);
    }

    private static double finitePercent(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(100.0, value)) : 0.0;
    }

    private static ReaderPosition bookmarkPosition(
            String paragraphId, double storedPercent, int charOffset, long totalTextLength) {
        if (paragraphId != null && paragraphId.startsWith("rp:")) {
            return ReaderPosition.parse(paragraphId.substring(3));
        }

        // Legacy bookmarks stored only a paragraph id + percentage. Keep them navigable.
        int paragraphIndex = 0;
        if (paragraphId != null && paragraphId.startsWith("p")) {
            try {
                paragraphIndex = Integer.parseInt(paragraphId.substring(1));
            } catch (NumberFormatException ignored) {
                paragraphIndex = 0;
            }
        }
        double percent = finitePercent(storedPercent);
        long offset = totalTextLength <= 0 ? 0L : Math.round(totalTextLength * percent / 100.0);
        return new ReaderPosition(0, offset, paragraphIndex, charOffset);
    }

    private static ReaderBookmarkItem toReaderBookmarkItem(Bookmark bookmark) {
        if (bookmark == null) return null;
        return new ReaderBookmarkItem(
                bookmark.getId(),
                bookmark.getBookId(),
                bookmark.getParagraphId(),
                bookmark.getCharOffset(),
                bookmark.getPosition(),
                bookmark.getChapterTitle(),
                bookmark.getContext(),
                bookmark.getCreatedAt());
    }

    private String activeCollectionId() {
        var active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null || active.getId().isBlank()) {
            throw new IllegalStateException("Reader state requires an active collection");
        }
        return active.getId();
    }

    private void requireActiveCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            throw new IllegalArgumentException("Reader state is missing collectionId");
        }
        var active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null || !expectedCollectionId.equals(active.getId())) {
            throw new IllegalStateException("Active collection changed before Reader state access");
        }
    }

    private <T> T withReaderState(String collectionId, Supplier<T> work) {
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.READER_STATE);
        try {
            requireActiveCollection(collectionId);
            T result = work.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private static String cacheKey(String collectionId, String bookId) {
        return String.valueOf(collectionId) + "\u0000" + String.valueOf(bookId);
    }
}