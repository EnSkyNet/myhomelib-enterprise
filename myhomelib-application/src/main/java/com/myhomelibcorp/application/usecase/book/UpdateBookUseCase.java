package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.event.book.BookUpdatedEvent;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpdateBookUseCase {

    private final BookCommandRepository bookCommandRepository;
    private final BookQueryRepository bookQueryRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(transactionManager = "collectionTransactionManager")
    public void execute(Book book) {
        if (book == null || book.getId() == null) {
            throw new IllegalArgumentException("Book and BookId must not be null");
        }

        Optional<Book> existing = bookQueryRepository.findById(book.getId());
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Book not found with id: " + book.getId());
        }

        Book savedBook = bookCommandRepository.save(book);

        BookSnapshot snapshot = BookSnapshot.fromBook(savedBook);
        BookUpdatedEvent event = new BookUpdatedEvent(snapshot);
        eventPublisher.publishEvent(event);

        log.debug("Книгу оновлено та опубліковано BookUpdatedEvent: id={}", savedBook.getId().asString());
    }

    @Transactional(transactionManager = "collectionTransactionManager")
    public void updateRate(BookId bookId, int rate) {
        if (bookId == null) {
            throw new IllegalArgumentException("BookId must not be null");
        }
        if (rate < 0 || rate > 5) {
            throw new IllegalArgumentException("Rate must be between 0 and 5");
        }

        log.debug("Оновлення рейтингу для книги {}: {}", bookId, rate);
        bookCommandRepository.updateRate(bookId, rate);
    }

    @Transactional(transactionManager = "collectionTransactionManager")
    public void updateProgress(BookId bookId, int progress) {
        if (bookId == null) {
            throw new IllegalArgumentException("BookId must not be null");
        }
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("Progress must be between 0 and 100");
        }

        log.debug("Оновлення прогресу для книги {}: {}%", bookId, progress);
        bookCommandRepository.updateProgress(bookId, progress);
    }
}