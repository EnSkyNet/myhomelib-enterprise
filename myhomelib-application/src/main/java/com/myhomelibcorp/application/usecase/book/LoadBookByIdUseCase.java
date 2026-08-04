package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoadBookByIdUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;

    public Optional<BookDto> execute(BookId bookId) {
        return bookQueryRepository.findById(bookId)
                .map(bookMapper::toDto);
    }
}