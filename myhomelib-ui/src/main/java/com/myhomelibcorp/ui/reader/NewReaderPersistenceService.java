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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewReaderPersistenceService {

    private final ReadingProgressRepository readingProgressRepository;
    private final BookmarkRepository bookmarkRepository;
    private final com.myhomelibcorp.ui.service.LocalizationService i18n;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;

    // Кеш останніх збережених позицій для перевірки змін
    private final ConcurrentMap<String, ReaderPosition> lastSavedPositions = new ConcurrentHashMap<>();

    // ==================== ПОЗИЦІЯ ====================


    /** Перевіряє, чи позиція істотно змінилася від останнього збереження. */
    private boolean isPositionChanged(String collectionId, String bookId, ReaderPosition newPos) {
        if (newPos == null) return false;
        ReaderPosition lastSaved = lastSavedPositions.get(cacheKey(collectionId, bookId));
        if (lastSaved == null) return true;
        boolean offsetChanged = Math.abs(lastSaved.textOffset() - newPos.textOffset()) > 5;
        boolean chapterChanged = lastSaved.chapterIndex() != newPos.chapterIndex();
        boolean paragraphChanged = lastSaved.paragraphIndex() != newPos.paragraphIndex();
        return offsetChanged || chapterChanged || paragraphChanged;
    }

    /** Зберігає позицію читання, якщо вона змінилася. */
    public boolean savePosition(String bookId, ReaderPosition position, long totalTextLength) {
        return savePosition(activeCollectionId(), bookId, position, totalTextLength);
    }

    /** Collection-stable variant used by autosavers and long-lived Reader workspaces. */
    public boolean savePosition(String collectionId, String bookId, ReaderPosition position, long totalTextLength) {
        if (bookId == null || position == null || !isPositionChanged(collectionId, bookId, position)) return true;
        try {
            return withReaderState(collectionId, () -> {
                Optional<ReadingProgressDto> existing = readingProgressRepository.findByBookId(bookId);
                ReadingProgressDto dto;
                if (existing.isPresent()) {
                    dto = existing.get();
                    dto.setAnchorId(position.serialize());
                    dto.setParagraphIndex(position.paragraphIndex());
                    dto.setParagraphId("p" + position.paragraphIndex());
                    dto.setCharOffset(position.charOffset());
                    dto.setPercent(position.getPercent(totalTextLength));
                    dto.setUpdatedAt(LocalDateTime.now());
                    dto.setLastDevice("desktop");
                } else {
                    dto = ReadingProgressDto.builder()
                            .bookId(bookId)
                            .anchorId(position.serialize())
                            .paragraphIndex(position.paragraphIndex())
                            .paragraphId("p" + position.paragraphIndex())
                            .charOffset(position.charOffset())
                            .percent(position.getPercent(totalTextLength))
                            .updatedAt(LocalDateTime.now())
                            .readingTimeSeconds(0)
                            .lastDevice("desktop")
                            .build();
                }
                readingProgressRepository.save(dto);
                lastSavedPositions.put(cacheKey(collectionId, bookId), position);
                return true;
            });
        } catch (Exception e) {
            log.warn("Reader position was not saved for collection={} book={}: {}", collectionId, bookId, e.getMessage());
            return false;
        }
    }

    /** Завантажує позицію читання з БД. */
    public Optional<ReaderPosition> loadPosition(String bookId) {
        return loadPosition(activeCollectionId(), bookId);
    }

    public Optional<ReaderPosition> loadPosition(String collectionId, String bookId) {
        if (bookId == null) return Optional.empty();
        try {
            return withReaderState(collectionId, () -> {
                Optional<ReadingProgressDto> dto = readingProgressRepository.findByBookId(bookId);
                if (dto.isPresent()) {
                    ReaderPosition position = ReaderPosition.parse(dto.get().getAnchorId());
                    lastSavedPositions.put(cacheKey(collectionId, bookId), position);
                    return Optional.of(position);
                }
                return Optional.empty();
            });
        } catch (Exception e) {
            throw new IllegalStateException(i18n.format("ui.reader.persistence.position_load_error", bookId), e);
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
        try {
            return withReaderState(collectionId, () -> {
                Optional<ReadingProgressDto> existing = readingProgressRepository.findByBookId(bookId);
                double percent = Math.min(100.0, Math.max(0.0, progressPercent));
                ReadingProgressDto dto = existing.orElseGet(() -> ReadingProgressDto.builder()
                        .bookId(bookId).readingTimeSeconds(0).build());
                dto.setAnchorId(position.serialize());
                dto.setParagraphIndex(position.chapterIndex());
                dto.setParagraphId("audio-track:" + position.trackIndex());
                dto.setCharOffset(0);
                dto.setPercent(percent);
                dto.setUpdatedAt(LocalDateTime.now());
                dto.setLastDevice("desktop");
                readingProgressRepository.save(dto);
                return true;
            });
        } catch (Exception e) {
            log.warn("Audiobook position was not saved for collection={} book={}: {}", collectionId, bookId, e.getMessage());
            return false;
        }
    }

    public Optional<AudioPosition> loadAudioPosition(String bookId) {
        return loadAudioPosition(activeCollectionId(), bookId);
    }

    public Optional<AudioPosition> loadAudioPosition(String collectionId, String bookId) {
        if (bookId == null) return Optional.empty();
        try {
            return withReaderState(collectionId, () -> readingProgressRepository.findByBookId(bookId)
                    .flatMap(dto -> AudioPosition.parse(dto.getAnchorId())));
        } catch (Exception e) {
            throw new IllegalStateException(i18n.format("ui.reader.persistence.position_load_error", bookId), e);
        }
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
                        .position(Math.max(0.0, Math.min(100.0, percent)))
                        .chapterTitle(title != null ? title : i18n.text("ui.reader.bookmark.default_title"))
                        .context(context != null ? context : "")
                        .createdAt(LocalDateTime.now())
                        .build();
                return bookmarkRepository.save(bookmark);
            });
        } catch (Exception e) {
            throw new IllegalStateException(i18n.format("ui.reader.persistence.bookmark_save_error", bookId), e);
        }
    }

    public Optional<AudioPosition> audioBookmarkToPosition(Bookmark bookmark) {
        return bookmark == null ? Optional.empty() : AudioPosition.parse(bookmark.getParagraphId());
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
            throw new IllegalStateException(i18n.format("ui.reader.persistence.bookmark_save_error", bookId), e);
        }
    }

    public List<Bookmark> loadBookmarks(String bookId) {
        if (bookId == null) {
            return List.of();
        }

        String collectionId = activeCollectionId();
        try {
            return withReaderState(collectionId, () -> bookmarkRepository.findByBookId(bookId));
        } catch (Exception e) {
            throw new IllegalStateException(i18n.format("ui.reader.persistence.bookmarks_load_error", bookId), e);
        }
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
            throw new IllegalStateException(i18n.format("ui.reader.persistence.bookmark_delete_error", bookmarkId), e);
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
            throw new IllegalStateException(i18n.format("ui.reader.persistence.bookmark_count_error", bookId), e);
        }
    }

    public ReaderPosition bookmarkToPosition(Bookmark bookmark, long totalTextLength) {
        if (bookmark == null) return ReaderPosition.start();
        String paragraphId = bookmark.getParagraphId();
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
        double percent = Math.max(0.0, Math.min(100.0, bookmark.getPosition()));
        long offset = totalTextLength <= 0 ? 0L : Math.round(totalTextLength * percent / 100.0);
        return new ReaderPosition(0, offset, paragraphIndex, bookmark.getCharOffset());
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