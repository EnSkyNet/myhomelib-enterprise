package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.event.book.BookUpdatedEvent;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
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

    /**
     * Оновлює книгу та публікує {@link BookUpdatedEvent}.
     * @param book книга з оновленими даними
     * @throws IllegalArgumentException якщо книга не існує
     */
    @Transactional
    public void execute(Book book) {
        if (book == null || book.getId() == null) {
            throw new IllegalArgumentException("Book and BookId must not be null");
        }

        // Перевіряємо, чи книга існує
        Optional<Book> existing = bookQueryRepository.findById(book.getId());
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Book not found with id: " + book.getId());
        }

        // Зберігаємо оновлену книгу
        Book savedBook = bookCommandRepository.save(book);

        // Публікуємо подію про оновлення
        BookSnapshot snapshot = BookSnapshot.fromBook(savedBook);
        BookUpdatedEvent event = new BookUpdatedEvent(snapshot);
        eventPublisher.publishEvent(event);

        log.debug("Книгу оновлено та опубліковано BookUpdatedEvent: id={}", savedBook.getId().asString());
    }

    /**
     * Оновлює рейтинг книги.
     */
    @Transactional
    public void updateRate(BookId bookId, int rate) {
        if (bookId == null) {
            throw new IllegalArgumentException("BookId must not be null");
        }

        Optional<Book> existing = bookQueryRepository.findById(bookId);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Book not found with id: " + bookId);
        }

        Book book = existing.get();
        Book updatedBook = book.changeMetadata(
                book.getMetadata().builder()
                        .rate(rate)
                        .build()
        );

        execute(updatedBook);
    }

    /**
     * Оновлює прогрес читання книги.
     */
    @Transactional
    public void updateProgress(BookId bookId, int progress) {
        if (bookId == null) {
            throw new IllegalArgumentException("BookId must not be null");
        }

        Optional<Book> existing = bookQueryRepository.findById(bookId);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Book not found with id: " + bookId);
        }

        Book book = existing.get();
        Book updatedBook = book.changeMetadata(
                book.getMetadata().builder()
                        .progress(progress)
                        .build()
        );

        execute(updatedBook);
    }
}