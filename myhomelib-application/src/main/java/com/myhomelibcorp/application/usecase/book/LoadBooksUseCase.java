package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LoadBooksUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;

    public List<BookDto> execute(int limit, int offset) {
        return bookQueryRepository.findAll(limit, offset).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }

    public int getTotalCount() {
        return bookQueryRepository.getTotalCount();
    }
}