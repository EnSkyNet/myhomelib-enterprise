package com.myhomelibcorp.application.imports.error;

import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Slf4j
public class ImportErrorHandler {

    public enum ErrorAction {
        SKIP_FILE,
        RETRY,
        STOP_IMPORT
    }

    public ErrorAction handleError(Path file, Exception e, int attempt) {
        log.error("Помилка імпорту файлу: {} (спроба {}): {}", file, attempt, e.getMessage());

        if (e instanceof java.io.IOException) {
            if (attempt < 3) {
                log.warn("Повторна спроба імпорту файлу: {} (спроба {})", file, attempt + 1);
                return ErrorAction.RETRY;
            }
        }

        if (e instanceof IllegalArgumentException || e.getCause() instanceof IllegalArgumentException) {
            log.warn("Непідтримуваний формат, файл пропущено: {}", file);
            return ErrorAction.SKIP_FILE;
        }

        log.error("Критична помилка, імпорт зупинено: {}", file, e);
        return ErrorAction.STOP_IMPORT;
    }

    public boolean handleBookError(Book book, Exception e) {
        log.error("Помилка збереження книги: {}", book.getTitle(), e);
        return true;
    }

    public void handleDuplicate(Book book) {
        log.warn("Дублікат книги: {}", book.getTitle());
    }
}