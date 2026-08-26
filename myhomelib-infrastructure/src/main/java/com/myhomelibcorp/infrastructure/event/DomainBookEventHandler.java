package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class DomainBookEventHandler {

    private final SearchIndexer searchIndexer;



    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBookDeleted(BookDeletedEvent event) {
        log.debug("Отримано доменну подію BookDeletedEvent для книги: {}", event.getBookId());
        searchIndexer.deleteBook(event.getBookId());
        searchIndexer.commit();
    }
}