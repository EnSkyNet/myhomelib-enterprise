package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.domain.event.book.BookAddedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookAddedEventHandler {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBookAdded(BookAddedEvent event) {
        log.debug("Книгу додано: id={}, title={}",
                event.getBookId(),
                event.getBookSnapshot() != null ? event.getBookSnapshot().getTitle() : "unknown");
        // Тут можна додати логіку:
        // - оновлення статистики
        // - оновлення кешів
        // - сповіщення плагінів
        // - синхронізація з хмарою
    }
}