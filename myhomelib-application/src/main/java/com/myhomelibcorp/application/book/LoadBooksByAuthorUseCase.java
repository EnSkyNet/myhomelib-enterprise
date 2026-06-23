package com.myhomelibcorp.application.book;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadBooksByAuthorUseCase {

    private final BookQueryRepository bookQueryRepository;

    public List<Book> loadByAuthor(AuthorId authorId, int limit, int offset) {
        return bookQueryRepository.findByAuthorId(authorId, limit, offset);
    }
}