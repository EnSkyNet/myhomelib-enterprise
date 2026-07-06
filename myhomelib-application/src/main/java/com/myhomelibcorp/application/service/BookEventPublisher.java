package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.domain.event.book.BookAddedEvent;
import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import com.myhomelibcorp.domain.event.book.BookMovedEvent;
import com.myhomelibcorp.domain.event.book.BookUpdatedEvent;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookEventPublisher {

    private final EventPublisher eventPublisher;

    public void publishBookAdded(BookSnapshot snapshot) {
        eventPublisher.publish(new BookAddedEvent(snapshot));
    }

    public void publishBookUpdated(BookSnapshot snapshot) {
        eventPublisher.publish(new BookUpdatedEvent(snapshot));
    }

    public void publishBookDeleted(BookId bookId) {
        eventPublisher.publish(new BookDeletedEvent(bookId));
    }

    public void publishBookMoved(BookId bookId, String oldFolder, String newFolder) {
        eventPublisher.publish(new BookMovedEvent(bookId, oldFolder, newFolder));
    }
}