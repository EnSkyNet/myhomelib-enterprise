package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderProgressManager {

    private final ReadingProgressRepository repository;
    private final ReaderJsBridge jsBridge;

    private BookDto currentBook;
    private ReadingProgressDto lastSaved = null;
    private double lastSavedPercent = -1;
    private boolean isReaderActive = false;

    public void setCurrentBook(BookDto book) {
        this.currentBook = book;
        this.isReaderActive = book != null;
        if (book == null) {
            log.debug("Reader деактивовано");
        } else {
            log.debug("Reader активовано для книги: {}", book.getTitle());
        }
    }

    public BookDto getCurrentBook() {
        return currentBook;
    }

    public boolean isReaderActive() {
        return isReaderActive && currentBook != null;
    }

    public void deactivateReader() {
        this.isReaderActive = false;
        this.currentBook = null;
        log.debug("Reader деактивовано");
    }

    public ReadingProgressDto getCurrentProgress(WebEngine engine) {
        // Перевірка активності Reader
        if (!isReaderActive() || engine == null) {
            log.trace("Reader неактивний, пропускаємо отримання прогресу");
            return null;
        }

        // Перевіряємо, чи завантажена сторінка
        if (engine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            log.trace("Сторінка ще не завантажена або не вдалося завантажити");
            return null;
        }

        // ДОДАТКОВА ПЕРЕВІРКА: чи не змінилася книга
        if (currentBook == null) {
            log.trace("Немає поточної книги");
            return null;
        }

        int index = jsBridge.getFirstVisibleParagraphIndex(engine);
        if (index < 0) {
            log.trace("Немає видимих абзаців (сторінка порожня або ще не завантажена)");
            return null;
        }

        int offset = jsBridge.getCharOffsetForParagraph(engine, index);
        double percent = jsBridge.getScrollPercent(engine);

        if (percent == 0 && index > 0) {
            percent = jsBridge.getParagraphPositionPercent(engine, index);
        }

        log.debug("Поточний прогрес: книга={}, абзац={}, зсув={}, %={}",
                currentBook.getId(), index, offset, percent);

        return ReadingProgressDto.builder()
                .bookId(currentBook.getId())
                .paragraphId(String.valueOf(index))
                .charOffset(offset)
                .percent(percent * 100)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public void saveProgress(ReadingProgressDto progress) {
        if (!isReaderActive()) {
            log.trace("Reader неактивний, пропускаємо збереження прогресу");
            return;
        }
        if (progress == null) return;

        int fixedOffset = Math.max(0, progress.getCharOffset());
        ReadingProgressDto toSave = ReadingProgressDto.builder()
                .bookId(progress.getBookId())
                .paragraphId(progress.getParagraphId())
                .charOffset(fixedOffset)
                .percent(progress.getPercent())
                .updatedAt(progress.getUpdatedAt())
                .build();
        repository.save(toSave);
        lastSaved = toSave;
        lastSavedPercent = toSave.getPercent();
        log.info("Збережено прогрес: книга={}, абзац={}, зсув={}, %={}",
                toSave.getBookId(), toSave.getParagraphId(), toSave.getCharOffset(), (int) toSave.getPercent());
    }

    public boolean shouldSave(ReadingProgressDto current) {
        if (!isReaderActive()) return false;
        if (current == null) return false;
        if (lastSaved == null) return true;
        if (!lastSaved.getParagraphId().equals(current.getParagraphId())) return true;
        if (Math.abs(lastSaved.getCharOffset() - current.getCharOffset()) > 10) return true;
        return Math.abs(lastSavedPercent - current.getPercent()) > 1.0;
    }

    public Optional<ReadingProgressDto> loadProgress(String bookId) {
        return repository.findByBookId(bookId);
    }

    public boolean restorePosition(WebEngine engine, String bookId) {
        if (!isReaderActive() || engine == null) {
            log.trace("Reader неактивний, пропускаємо відновлення позиції");
            return false;
        }

        // Перевіряємо, чи завантажена сторінка
        if (engine.getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            log.trace("Сторінка не завантажена, пропускаємо відновлення");
            return false;
        }

        Optional<ReadingProgressDto> opt = loadProgress(bookId);
        if (opt.isEmpty()) {
            log.info("Немає збереженої позиції для книги {}", bookId);
            return false;
        }

        ReadingProgressDto progress = opt.get();
        lastSaved = progress;
        lastSavedPercent = progress.getPercent();

        int index = extractIndex(progress.getParagraphId());
        int offset = Math.max(0, progress.getCharOffset());

        int total = jsBridge.getParagraphCount(engine);
        if (index >= total) {
            log.warn("Індекс {} виходить за межі (всього {} абзаців). Використовуємо відсоток для прокрутки.",
                    index, total);
            double percent = progress.getPercent() / 100.0;
            String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + percent + ")";
            engine.executeScript(script);
            int newIndex = jsBridge.getFirstVisibleParagraphIndex(engine);
            if (newIndex >= 0) {
                index = newIndex;
            } else {
                index = total - 1;
            }
        }
        if (index < 0) index = 0;

        boolean success = jsBridge.scrollToParagraph(engine, index, offset);
        if (success) {
            log.info("Відновлено позицію: книга={}, абзац={}, зсув={}, %={}",
                    bookId, index, offset, progress.getPercent());
        } else {
            log.warn("Не вдалося відновити позицію для індексу {}", index);
        }
        return success;
    }

    private int extractIndex(String paragraphId) {
        if (paragraphId == null) return 0;
        if (paragraphId.startsWith("p")) {
            try {
                return Integer.parseInt(paragraphId.substring(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(paragraphId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}