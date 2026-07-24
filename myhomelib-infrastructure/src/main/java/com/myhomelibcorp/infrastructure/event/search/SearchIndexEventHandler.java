package com.myhomelibcorp.infrastructure.event.search;

import com.myhomelibcorp.application.event.BooksImportedBatchEvent;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.infrastructure.event.SimpleEventBus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchIndexEventHandler {

    private final SimpleEventBus eventBus;
    private final SearchIndexer searchIndexer;

    @PostConstruct
    public void init() {
        // Замість окремих подій BookImportedEvent слухаємо батчеві події
        eventBus.register(BooksImportedBatchEvent.class, this::handleBooksImportedBatch);
        log.info("SearchIndexEventHandler зареєстровано для батчевих подій імпорту");
    }

    private void handleBooksImportedBatch(BooksImportedBatchEvent event) {
        var books = event.books();
        if (books == null || books.isEmpty()) {
            log.debug("Отримано порожній батч імпорту – пропускаємо індексацію");
            return;
        }

        log.info("Отримано батч імпорту з {} книг. Індексація...", books.size());

        try {
            // Індексуємо всі книги батчем
            searchIndexer.indexAll(books);
            // Коміт виконується всередині indexAll, але для впевненості викличемо ще раз
            searchIndexer.commit();
            log.info("Батч з {} книг успішно проіндексовано", books.size());
        } catch (Exception e) {
            log.error("Помилка індексації батча з {} книг", books.size(), e);
            // Помилка не повинна зупиняти весь імпорт, але ми логуємо
        }
    }
}