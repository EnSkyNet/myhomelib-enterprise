package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadBooksUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;

    public PageResult<BookDto> execute(BookQuery query) {
        PageResult<Book> result = bookQueryRepository.findPage(query);
        List<BookDto> dtos = result.content().stream().map(bookMapper::toDto).toList();
        return new PageResult<>(
                dtos,
                result.totalElements(),
                result.totalPages(),
                result.currentPage(),
                result.size()
        );
    }
}
