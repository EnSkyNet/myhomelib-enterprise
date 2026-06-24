package com.myhomelibcorp.application.genre;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadBooksByGenreUseCase {

    private final BookQueryRepository bookQueryRepository;

    public List<Book> loadByGenre(String genreCode, int limit, int offset) {
        return bookQueryRepository.findByGenre(genreCode, limit, offset);
    }
}