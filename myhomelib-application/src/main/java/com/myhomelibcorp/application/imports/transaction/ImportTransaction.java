package com.myhomelibcorp.application.imports.transaction;

import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportTransaction {

    private final TransactionTemplate transactionTemplate;
    private final BookSaver bookSaver;

    public int saveBatchInTransaction(List<Book> books, boolean indexAfterSave, DuplicatePolicy policy) {
        return transactionTemplate.execute(status -> {
            return bookSaver.saveBatch(books, indexAfterSave, policy);
        });
    }

    /**
     * Зберігає книги з чанкуванням (для великих обсягів).
     */
    public int saveBatchWithChunking(List<Book> books, int chunkSize, boolean indexAfterSave, DuplicatePolicy policy) {
        if (books == null || books.isEmpty()) {
            return 0;
        }

        List<Book> chunk = new ArrayList<>(chunkSize);
        int totalSaved = 0;

        for (Book book : books) {
            chunk.add(book);
            if (chunk.size() >= chunkSize) {
                totalSaved += transactionTemplate.execute(status -> {
                    return bookSaver.saveBatch(chunk, indexAfterSave, policy);
                });
                chunk.clear();
                log.debug("Збережено частину: {} книг (всього {})", chunkSize, totalSaved);
            }
        }

        if (!chunk.isEmpty()) {
            totalSaved += transactionTemplate.execute(status -> {
                return bookSaver.saveBatch(chunk, indexAfterSave, policy);
            });
            log.debug("Збережено останню частину: {} книг (всього {})", chunk.size(), totalSaved);
        }

        log.info("Всього збережено: {} книг", totalSaved);
        return totalSaved;
    }
}