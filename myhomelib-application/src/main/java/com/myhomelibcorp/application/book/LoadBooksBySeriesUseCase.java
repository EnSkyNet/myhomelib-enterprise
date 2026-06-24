package com.myhomelibcorp.application.book;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadBooksBySeriesUseCase {

    private final BookQueryRepository bookQueryRepository;

    public List<Book> loadBySeries(String seriesName, int limit, int offset) {
        return bookQueryRepository.findBySeries(seriesName, limit, offset);
    }
}