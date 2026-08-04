package com.myhomelibcorp.application.imports.transaction;

import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportTransaction {

    private final @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate;
    private final BookSaver bookSaver;

    /**
     * Зберігає книги в одній транзакції (для великих обсягів).
     * Використовується для всього файлу.
     */
    public int saveAllInTransaction(List<Book> books, boolean indexAfterSave, DuplicatePolicy policy) {
        if (books == null || books.isEmpty()) {
            return 0;
        }
        log.info("Збереження {} книг в одній транзакції", books.size());
        return transactionTemplate.execute(status -> {
            return bookSaver.saveBatch(books, indexAfterSave, policy);
        });
    }

    /**
     * @deprecated Використовуйте {@link #saveAllInTransaction(List, boolean, DuplicatePolicy)}
     * для великих обсягів.
     */
    @Deprecated
    public int saveBatchInTransaction(List<Book> books, boolean indexAfterSave, DuplicatePolicy policy) {
        return transactionTemplate.execute(status -> {
            return bookSaver.saveBatch(books, indexAfterSave, policy);
        });
    }

    /**
     * Зберігає книги з чанкуванням (для великих обсягів).
     * Використовує ОДНУ транзакцію для всіх чанків.
     */
    public int saveBatchWithChunking(List<Book> books, int chunkSize, boolean indexAfterSave, DuplicatePolicy policy) {
        if (books == null || books.isEmpty()) {
            return 0;
        }

        List<Book> chunk = new ArrayList<>(chunkSize);
        int totalSaved = 0;

        // Збираємо всі книги в один список для однієї транзакції
        List<Book> allBooks = new ArrayList<>();

        for (Book book : books) {
            chunk.add(book);
            if (chunk.size() >= chunkSize) {
                allBooks.addAll(chunk);
                chunk.clear();
            }
        }

        if (!chunk.isEmpty()) {
            allBooks.addAll(chunk);
        }

        log.info("Збереження {} книг в одній транзакції (з чанкуванням)", allBooks.size());
        return saveAllInTransaction(allBooks, indexAfterSave, policy);
    }
}