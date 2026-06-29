package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LoadBooksByAuthorUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;

    public List<BookDto> execute(AuthorId authorId, int limit, int offset) {
        return bookQueryRepository.findByAuthorId(authorId, limit, offset).stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
    }
}