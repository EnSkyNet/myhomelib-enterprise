package com.myhomelibcorp.application.book;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadBooksUseCase {

    private final BookQueryRepository bookQueryRepository;

    public List<Book> loadAll(int limit, int offset) {
        return bookQueryRepository.findAll(limit, offset);
    }

    public int getTotalCount() {
        return bookQueryRepository.getTotalCount();
    }
}