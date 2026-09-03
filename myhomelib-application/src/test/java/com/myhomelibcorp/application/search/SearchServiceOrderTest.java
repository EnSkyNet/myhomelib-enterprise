package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.mapper.GenreMapper;
import com.myhomelibcorp.application.port.out.cache.SearchCache;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.search.SearchQueryService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SearchServiceOrderTest {

    @Test
    void preservesLuceneIdOrderWhenRepositoryReturnsSqlInOrder() {
        SearchQueryService queryService = mock(SearchQueryService.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookMapper mapper = mock(BookMapper.class);
        SearchService service = new SearchService(
                queryService,
                books,
                mapper,
                mock(SearchCache.class),
                mock(GenreRepository.class),
                mock(SeriesRepository.class),
                mock(AuthorRepository.class),
                mock(AuthorMapper.class),
                mock(GenreMapper.class),
                mock(BookFilterStateService.class));

        BookId firstId = BookId.generate();
        BookId secondId = BookId.generate();
        Book first = mock(Book.class);
        Book second = mock(Book.class);
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        BookDto firstDto = BookDto.builder().id(firstId.asString()).title("First relevance").build();
        BookDto secondDto = BookDto.builder().id(secondId.asString()).title("Second relevance").build();
        when(mapper.toDto(first)).thenReturn(firstDto);
        when(mapper.toDto(second)).thenReturn(secondDto);

        SearchRequest request = SearchRequest.builder()
                .text("needle")
                .filterSpec(BookFilterSpec.empty())
                .limit(2)
                .build();
        when(queryService.search(any(SearchRequest.class)))
                .thenReturn(new SearchResult(List.of(firstId, secondId), 2, 0, 2, 1));
        when(books.findListItemsByIds(List.of(firstId, secondId))).thenReturn(List.of(second, first));

        assertThat(service.search(request))
                .extracting(BookDto::getTitle)
                .containsExactly("First relevance", "Second relevance");
    }
    @Test
    void continuationPageSkipsRepeatedTotalCountAndKeepsKnownTotal() {
        SearchQueryService queryService = mock(SearchQueryService.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookMapper mapper = mock(BookMapper.class);
        BookFilterStateService filters = mock(BookFilterStateService.class);
        when(filters.current()).thenReturn(BookFilterSpec.empty());
        SearchService service = new SearchService(
                queryService,
                books,
                mapper,
                mock(SearchCache.class),
                mock(GenreRepository.class),
                mock(SeriesRepository.class),
                mock(AuthorRepository.class),
                mock(AuthorMapper.class),
                mock(GenreMapper.class),
                filters);

        BookId id = BookId.generate();
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        BookDto dto = BookDto.builder().id(id.asString()).title("Continuation").build();
        when(mapper.toDto(book)).thenReturn(dto);
        when(books.findListItemsByIds(List.of(id))).thenReturn(List.of(book));
        when(queryService.search(any(SearchRequest.class)))
                .thenReturn(new SearchResult(List.of(id), -1, 1, 500, 1));

        SearchRequest base = SearchRequest.builder()
                .text("needle")
                .filterSpec(BookFilterSpec.empty())
                .build();
        var page = service.searchPage(base, 500, 500, 1203);

        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(queryService).search(captor.capture());
        assertThat(captor.getValue().trackTotalHits()).isFalse();
        assertThat(captor.getValue().offset()).isEqualTo(500);
        assertThat(page.totalElements()).isEqualTo(1203);
        assertThat(page.content()).containsExactly(dto);
    }

}
