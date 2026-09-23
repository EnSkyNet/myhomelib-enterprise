package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.content.indexing.ContentIndexAutoSchedulingService;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
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
    private final ContentIndexAutoSchedulingService contentIndexScheduling;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBookDeleted(BookDeletedEvent event) {
        log.debug("Отримано доменну подію BookDeletedEvent для книги: {}", event.getBookId());
        searchIndexer.deleteBook(event.getBookId());
        searchIndexer.commit();
        if (event.getBookId() != null) {
            try {
                contentIndexScheduling.scheduleCurrentCollection(new ImportChangeSet(
                        java.util.Set.of(), java.util.Set.of(),
                        java.util.Set.of(event.getBookId().asString()), true));
            } catch (RuntimeException failure) {
                contentIndexScheduling.markCurrentCollectionStale();
                log.error("Не вдалося синхронізувати full-text індекс після видалення книги {}", event.getBookId(), failure);
            }
        }
    }
}