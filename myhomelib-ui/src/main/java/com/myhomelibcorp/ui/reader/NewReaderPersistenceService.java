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

    // Кеш останніх збережених позицій для перевірки змін
    private final ConcurrentMap<String, ReaderPosition> lastSavedPositions = new ConcurrentHashMap<>();

    // ==================== ПОЗИЦІЯ ====================

    /**
     * Перевіряє, чи змінилася позиція з моменту останнього збереження.
     */
    private boolean isPositionChanged(String bookId, ReaderPosition newPos) {
        if (newPos == null) {
            return false;
        }

        ReaderPosition lastSaved = lastSavedPositions.get(bookId);
        if (lastSaved == null) {
            return true;
        }

        // Перевіряємо зміну offset (головний критерій)
        boolean offsetChanged = Math.abs(lastSaved.textOffset() - newPos.textOffset()) > 5;

        // Перевіряємо зміну chapter
        boolean chapterChanged = lastSaved.chapterIndex() != newPos.chapterIndex();

        // Перевіряємо зміну percent (якщо offset не змінився)
        boolean percentChanged = Math.abs(lastSaved.getPercent(1000) - newPos.getPercent(1000)) > 1.0;

        boolean changed = offsetChanged || chapterChanged || percentChanged;

        if (changed) {
            log.trace("📊 Позиція змінилася: offset {} -> {}, chapter {} -> {}",
                    lastSaved.textOffset(), newPos.textOffset(),
                    lastSaved.chapterIndex(), newPos.chapterIndex());
        }

        return changed;
    }

    /**
     * Зберігає позицію читання в БД (тільки якщо вона змінилася).
     */
    public void savePosition(String bookId, ReaderPosition position) {
        if (bookId == null || position == null) {
            return;
        }

        // Перевіряємо, чи змінилася позиція
        if (!isPositionChanged(bookId, position)) {
            log.trace("⏭️ Позиція не змінилася, пропускаємо збереження");
            return;
        }

        try {
            Optional<ReadingProgressDto> existing = readingProgressRepository.findByBookId(bookId);
            ReadingProgressDto dto;

            if (existing.isPresent()) {
                dto = existing.get();
                dto.setAnchorId(position.serialize());
                dto.setParagraphIndex(position.paragraphIndex());
                dto.setParagraphId("p" + position.paragraphIndex());
                dto.setCharOffset(position.charOffset());
                dto.setPercent(position.getPercent(1000));
                dto.setUpdatedAt(LocalDateTime.now());
            } else {
                dto = ReadingProgressDto.builder()
                        .bookId(bookId)
                        .anchorId(position.serialize())
                        .paragraphIndex(position.paragraphIndex())
                        .paragraphId("p" + position.paragraphIndex())
                        .charOffset(position.charOffset())
                        .percent(position.getPercent(1000))
                        .updatedAt(LocalDateTime.now())
                        .readingTimeSeconds(0)
                        .build();
            }

            readingProgressRepository.save(dto);

            // Оновлюємо кеш останньої збереженої позиції
            lastSavedPositions.put(bookId, position);

            log.debug("💾 Позицію збережено в БД: book={}, offset={}, chapter={}, percent={}%",
                    bookId, position.textOffset(), position.chapterIndex(),
                    Math.round(position.getPercent(1000)));

        } catch (Exception e) {
            log.error("Помилка збереження позиції в БД: {}", e.getMessage());
        }
    }

    /**
     * Завантажує позицію читання з БД.
     */
    public Optional<ReaderPosition> loadPosition(String bookId) {
        if (bookId == null) {
            return Optional.empty();
        }

        try {
            Optional<ReadingProgressDto> dto = readingProgressRepository.findByBookId(bookId);
            if (dto.isPresent()) {
                ReadingProgressDto progress = dto.get();
                ReaderPosition position = ReaderPosition.parse(progress.getAnchorId());

                // Зберігаємо в кеш
                lastSavedPositions.put(bookId, position);

                log.debug("📖 Позицію завантажено з БД: book={}, offset={}, chapter={}",
                        bookId, position.textOffset(), position.chapterIndex());
                return Optional.of(position);
            }
        } catch (Exception e) {
            log.error("Помилка завантаження позиції з БД: {}", e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Видаляє позицію читання з БД.
     */
    public void deletePosition(String bookId) {
        if (bookId == null) {
            return;
        }
        try {
            readingProgressRepository.deleteByBookId(bookId);
            lastSavedPositions.remove(bookId);
            log.debug("🗑️ Позицію видалено з БД: book={}", bookId);
        } catch (Exception e) {
            log.error("Помилка видалення позиції з БД: {}", e.getMessage());
        }
    }

    /**
     * Очищує кеш позицій (при зміні колекції тощо).
     */
    public void clearCache() {
        lastSavedPositions.clear();
        log.debug("🧹 Кеш позицій очищено");
    }

    // ==================== ЗАКЛАДКИ ====================

    public Bookmark saveBookmark(String bookId, ReaderPosition position, String title, String context) {
        if (bookId == null || position == null) {
            return null;
        }

        try {
            String paragraphId = "p" + position.paragraphIndex();
            double posPercent = position.getPercent(1000) / 100.0;

            Bookmark bookmark = Bookmark.builder()
                    .id(UUID.randomUUID().toString())
                    .bookId(bookId)
                    .paragraphId(paragraphId)
                    .charOffset(position.charOffset())
                    .position(posPercent)
                    .chapterTitle(title != null ? title : "Розділ " + (position.chapterIndex() + 1))
                    .context(context != null ? context : "")
                    .createdAt(LocalDateTime.now())
                    .build();

            Bookmark saved = bookmarkRepository.save(bookmark);
            log.info("⭐ Закладку збережено в БД: book={}, id={}", bookId, saved.getId());
            return saved;

        } catch (Exception e) {
            log.error("Помилка збереження закладки в БД: {}", e.getMessage());
            return null;
        }
    }

    public List<Bookmark> loadBookmarks(String bookId) {
        if (bookId == null) {
            return List.of();
        }

        try {
            return bookmarkRepository.findByBookId(bookId);
        } catch (Exception e) {
            log.error("Помилка завантаження закладок з БД: {}", e.getMessage());
            return List.of();
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
            log.error("Помилка видалення закладки з БД: {}", e.getMessage());
        }
    }

    public void deleteBookmarks(String bookId) {
        if (bookId == null) {
            return;
        }

        try {
            bookmarkRepository.deleteByBookId(bookId);
            log.debug("🗑️ Всі закладки видалено з БД для книги {}", bookId);
        } catch (Exception e) {
            log.error("Помилка видалення закладок з БД: {}", e.getMessage());
        }
    }

    public boolean hasBookmarks(String bookId) {
        if (bookId == null) {
            return false;
        }

        try {
            return bookmarkRepository.countByBookId(bookId) > 0;
        } catch (Exception e) {
            log.error("Помилка перевірки закладок: {}", e.getMessage());
            return false;
        }
    }

    public int getBookmarkCount(String bookId) {
        if (bookId == null) {
            return 0;
        }

        try {
            return bookmarkRepository.countByBookId(bookId);
        } catch (Exception e) {
            log.error("Помилка підрахунку закладок: {}", e.getMessage());
            return 0;
        }
    }

    public ReaderPosition bookmarkToPosition(Bookmark bookmark) {
        if (bookmark == null) {
            return ReaderPosition.start();
        }

        int paragraphIndex = 0;
        String paragraphId = bookmark.getParagraphId();
        if (paragraphId != null && paragraphId.startsWith("p")) {
            try {
                paragraphIndex = Integer.parseInt(paragraphId.substring(1));
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        long offset = (long) (bookmark.getPosition() * 1000);

        return new ReaderPosition(
                0,
                offset,
                paragraphIndex,
                bookmark.getCharOffset()
        );
    }
}