package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.reader.api.ReaderPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewReaderPersistenceService {

    private final ReadingProgressRepository readingProgressRepository;
    private final BookmarkRepository bookmarkRepository;
    private final com.myhomelibcorp.ui.service.LocalizationService i18n;

    // Кеш останніх збережених позицій для перевірки змін
    private final ConcurrentMap<String, ReaderPosition> lastSavedPositions = new ConcurrentHashMap<>();

    // ==================== ПОЗИЦІЯ ====================


    /** Перевіряє, чи позиція істотно змінилася від останнього збереження. */
    private boolean isPositionChanged(String bookId, ReaderPosition newPos) {
        if (newPos == null) return false;
        ReaderPosition lastSaved = lastSavedPositions.get(bookId);
        if (lastSaved == null) return true;
        boolean offsetChanged = Math.abs(lastSaved.textOffset() - newPos.textOffset()) > 5;
        boolean chapterChanged = lastSaved.chapterIndex() != newPos.chapterIndex();
        boolean paragraphChanged = lastSaved.paragraphIndex() != newPos.paragraphIndex();
        return offsetChanged || chapterChanged || paragraphChanged;
    }

    /** Зберігає позицію читання, якщо вона змінилася. */
    public boolean savePosition(String bookId, ReaderPosition position, long totalTextLength) {
        if (bookId == null || position == null || !isPositionChanged(bookId, position)) return true;
        try {
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
                        .build();
            }
            readingProgressRepository.save(dto);
            lastSavedPositions.put(bookId, position);
            return true;
        } catch (Exception e) {
            log.error("Помилка збереження позиції в БД: {}", e.getMessage());
            return false;
        }
    }

    /** Завантажує позицію читання з БД. */
    public Optional<ReaderPosition> loadPosition(String bookId) {
        if (bookId == null) return Optional.empty();
        try {
            Optional<ReadingProgressDto> dto = readingProgressRepository.findByBookId(bookId);
            if (dto.isPresent()) {
                ReaderPosition position = ReaderPosition.parse(dto.get().getAnchorId());
                lastSavedPositions.put(bookId, position);
                return Optional.of(position);
            }
        } catch (Exception e) {
            throw new IllegalStateException(i18n.format("ui.reader.persistence.position_load_error", bookId), e);
        }
        return Optional.empty();
    }

    /**
     * Очищує кеш позицій (при зміні колекції тощо).
     */
    public void clearCache() {
        lastSavedPositions.clear();
        log.debug("🧹 Кеш позицій очищено");
    }

    // ==================== ЗАКЛАДКИ ====================

    public Bookmark saveBookmark(String bookId, ReaderPosition position, long totalTextLength, String title, String context) {
        if (bookId == null || position == null) {
            throw new IllegalArgumentException("bookId and position are required");
        }

        try {
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
            log.info("⭐ Закладку збережено в БД: book={}, id={}", bookId, saved.getId());
            return saved;

        } catch (Exception e) {
            throw new IllegalStateException(i18n.format("ui.reader.persistence.bookmark_save_error", bookId), e);
        }
    }

    public List<Bookmark> loadBookmarks(String bookId) {
        if (bookId == null) {
            return List.of();
        }

        try {
            return bookmarkRepository.findByBookId(bookId);
        } catch (Exception e) {
            throw new IllegalStateException(i18n.format("ui.reader.persistence.bookmarks_load_error", bookId), e);
        }
    }

    public void deleteBookmark(String bookmarkId) {
        if (bookmarkId == null) {
            return;
        }

        try {
            bookmarkRepository.deleteById(bookmarkId);
            log.debug("🗑️ Закладку видалено з БД: id={}", bookmarkId);
        } catch (Exception e) {
            throw new IllegalStateException(i18n.format("ui.reader.persistence.bookmark_delete_error", bookmarkId), e);
        }
    }


    public int getBookmarkCount(String bookId) {
        if (bookId == null) {
            return 0;
        }

        try {
            return bookmarkRepository.countByBookId(bookId);
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
}