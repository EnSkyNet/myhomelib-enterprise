package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.session.ReaderSession;
import javafx.concurrent.Worker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderProgressManager {

    private final ReadingProgressRepository repository;
    private final ReaderJsBridge jsBridge;
    private final CollectionLifecyclePort collectionLifecyclePort;

    private final ConcurrentMap<String, ReadingProgressDto> lastSavedProgress = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Double> lastSavedPercent = new ConcurrentHashMap<>();

    public ReadingProgressDto getCurrentProgress(ReaderSession session) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return null;
        }

        if (session.getWebEngine().getLoadWorker().getState() != Worker.State.SUCCEEDED) {
            return null;
        }

        if (session.getBook() == null) {
            return null;
        }

        int index = jsBridge.getFirstVisibleParagraphIndex(session.getWebEngine());
        if (index < 0) {
            return null;
        }

        int offset = jsBridge.getCharOffsetForParagraph(session.getWebEngine(), index);
        double percent = jsBridge.getScrollPercent(session.getWebEngine());

        if (percent == 0 && index > 0) {
            percent = jsBridge.getParagraphPositionPercent(session.getWebEngine(), index);
        }

        return ReadingProgressDto.builder()
                .bookId(session.getBookId())
                .paragraphId(String.valueOf(index))
                .charOffset(offset)
                .percent(percent * 100)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public void saveProgress(ReaderSession session, ReadingProgressDto progress) {
        if (session == null || !session.isActive()) {
            return;
        }

        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return;
        }

        if (progress == null) {
            return;
        }

        String sessionId = session.getSessionId();
        int fixedOffset = Math.max(0, progress.getCharOffset());

        ReadingProgressDto toSave = ReadingProgressDto.builder()
                .bookId(progress.getBookId())
                .paragraphId(progress.getParagraphId())
                .charOffset(fixedOffset)
                .percent(progress.getPercent())
                .updatedAt(progress.getUpdatedAt())
                .build();

        repository.save(toSave);
        lastSavedProgress.put(sessionId, toSave);
        lastSavedPercent.put(sessionId, toSave.getPercent());

        log.info("Збережено прогрес: книга={}, абзац={}, зсув={}, %={}",
                toSave.getBookId(), toSave.getParagraphId(), toSave.getCharOffset(), (int) toSave.getPercent());
    }

    public boolean shouldSave(ReaderSession session, ReadingProgressDto current) {
        if (session == null || !session.isActive()) {
            return false;
        }
        if (current == null) {
            return false;
        }

        String sessionId = session.getSessionId();
        ReadingProgressDto lastSaved = lastSavedProgress.get(sessionId);

        if (lastSaved == null) {
            return true;
        }
        if (!lastSaved.getParagraphId().equals(current.getParagraphId())) {
            return true;
        }
        if (Math.abs(lastSaved.getCharOffset() - current.getCharOffset()) > 10) {
            return true;
        }

        Double lastPercent = lastSavedPercent.get(sessionId);
        return lastPercent == null || Math.abs(lastPercent - current.getPercent()) > 1.0;
    }

    public Optional<ReadingProgressDto> loadProgress(String bookId) {
        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return Optional.empty();
        }
        return repository.findByBookId(bookId);
    }

    public boolean restorePosition(ReaderSession session, String bookId) {
        if (session == null || session.getWebEngine() == null || !session.isActive()) {
            return false;
        }

        if (collectionLifecyclePort == null || !collectionLifecyclePort.hasActiveCollection()) {
            return false;
        }

        if (!jsBridge.isContentLoaded(session.getWebEngine())) {
            log.debug("Контент не завантажено, пропускаємо відновлення");
            return false;
        }

        Optional<ReadingProgressDto> opt = loadProgress(bookId);
        if (opt.isEmpty()) {
            try {
                session.getWebEngine().executeScript("window.scrollTo(0, 0)");
                log.info("Прокручено на початок книги");
                return true;
            } catch (Exception e) {
                log.warn("Не вдалося прокрутити на початок", e);
                return false;
            }
        }

        ReadingProgressDto progress = opt.get();
        lastSavedProgress.put(session.getSessionId(), progress);
        lastSavedPercent.put(session.getSessionId(), progress.getPercent());

        int index = extractIndex(progress.getParagraphId());
        int offset = Math.max(0, progress.getCharOffset());

        int total = jsBridge.getParagraphCount(session.getWebEngine());
        log.info("Всього параграфів: {}, індекс: {}, зсув: {}", total, index, offset);

        if (total == 0) {
            double percent = progress.getPercent() / 100.0;
            String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + percent + ")";
            try {
                session.getWebEngine().executeScript(script);
                return true;
            } catch (Exception e) {
                log.warn("Не вдалося прокрутити за відсотком", e);
                return false;
            }
        }

        if (index >= total) {
            double percent = progress.getPercent() / 100.0;
            String script = "window.scrollTo(0, (document.documentElement.scrollHeight - document.documentElement.clientHeight) * " + percent + ")";
            try {
                session.getWebEngine().executeScript(script);
                int newIndex = jsBridge.getFirstVisibleParagraphIndex(session.getWebEngine());
                if (newIndex >= 0) {
                    index = newIndex;
                } else {
                    index = total - 1;
                }
            } catch (Exception e) {
                log.warn("Не вдалося прокрутити за відсотком", e);
                index = total - 1;
            }
        }
        if (index < 0) {
            index = 0;
        }

        boolean success = jsBridge.scrollToParagraph(session.getWebEngine(), index, offset);
        if (success) {
            log.info("Відновлено позицію: книга={}, абзац={}, зсув={}, %={}",
                    bookId, index, offset, progress.getPercent());
        } else {
            log.warn("Не вдалося відновити позицію для індексу {}", index);
        }
        return success;
    }

    public void deactivateReader(String sessionId) {
        lastSavedProgress.remove(sessionId);
        lastSavedPercent.remove(sessionId);
        log.debug("Reader деактивовано для сесії: {}", sessionId);
    }

    private int extractIndex(String paragraphId) {
        if (paragraphId == null) {
            return 0;
        }
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