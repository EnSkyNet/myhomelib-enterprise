package com.myhomelibcorp.application.author;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorService {

    private final AuthorRepository authorRepository;
    private final BookQueryRepository bookQueryRepository;
    private final AuthorMapper authorMapper;
    private final BookMapper bookMapper;

    public CompletableFuture<List<AuthorDto>> getAllAuthors() {
        return CompletableFuture.supplyAsync(() ->
                authorRepository.findAll().stream()
                        .map(authorMapper::toDto)
                        .collect(Collectors.toList())
        );
    }

    public CompletableFuture<List<BookDto>> getBooksByAuthor(AuthorId authorId) {
        return CompletableFuture.supplyAsync(() -> {
            BookQuery query = BookQuery.builder()
                    .authorId(authorId)
                    .pagination(Pagination.of(1000, 0))
                    .sortBy(SortBy.TITLE)
                    .direction(SortDirection.ASC)
                    .build();
            return bookQueryRepository.find(query).stream()
                    .map(bookMapper::toDto)
                    .collect(Collectors.toList());
        });
    }

    public CompletableFuture<List<BookDto>> getBooksByAuthorWithFilter(AuthorId authorId, String filter, String sortBy) {
        return CompletableFuture.supplyAsync(() -> {
            BookQuery.Builder builder = BookQuery.builder()
                    .authorId(authorId)
                    .pagination(Pagination.of(1000, 0));

            if (filter != null && !filter.isBlank()) {
                builder.text(filter);
            }

            if ("year".equals(sortBy)) {
                builder.sortBy(SortBy.DATE);
            } else if ("rating".equals(sortBy)) {
                builder.sortBy(SortBy.RATING);
            } else {
                builder.sortBy(SortBy.TITLE);
            }
            builder.direction(SortDirection.ASC);

            return bookQueryRepository.find(builder.build()).stream()
                    .map(bookMapper::toDto)
                    .collect(Collectors.toList());
        });
    }
}