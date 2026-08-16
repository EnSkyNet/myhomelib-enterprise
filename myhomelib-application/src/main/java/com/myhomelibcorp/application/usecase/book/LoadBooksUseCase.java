package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoadBooksUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;

    public PageResult<BookDto> execute(BookQuery query) {
        PageResult<Book> result = bookQueryRepository.findPage(query);
        List<BookDto> dtos = result.content().stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
        return new PageResult<>(
                dtos,
                result.totalElements(),
                result.totalPages(),
                result.currentPage(),
                result.size()
        );
    }

    public long count(BookQuery query) {
        return bookQueryRepository.count(query);
    }

    // ===== Зручні методи для різних типів запитів =====

    public PageResult<BookDto> loadByAuthor(AuthorId authorId, int page, int size) {
        BookQuery query = BookQuery.builder()
                .authorId(authorId)
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(size, page * size))
                .build();
        return execute(query);
    }

    public PageResult<BookDto> loadBySeries(SeriesId seriesId, int page, int size) {
        BookQuery query = BookQuery.builder()
                .seriesId(seriesId)
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(size, page * size))
                .build();
        return execute(query);
    }

    public PageResult<BookDto> loadByGenre(GenreId genreId, int page, int size) {
        BookQuery query = BookQuery.builder()
                .genreId(genreId)
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(size, page * size))
                .build();
        return execute(query);
    }

    public PageResult<BookDto> loadByGroup(GroupId groupId, int page, int size) {
        BookQuery query = BookQuery.builder()
                .groupId(groupId)
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(size, page * size))
                .build();
        return execute(query);
    }

    public PageResult<BookDto> loadAll(int page, int size) {
        BookQuery query = BookQuery.builder()
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(size, page * size))
                .build();
        return execute(query);
    }

    public PageResult<BookDto> loadByLanguage(LanguageCode language, int page, int size) {
        BookQuery query = BookQuery.builder()
                .language(language)
                .pagination(com.myhomelibcorp.application.query.common.Pagination.of(size, page * size))
                .build();
        return execute(query);
    }
}