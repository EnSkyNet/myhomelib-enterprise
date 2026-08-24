package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.exchange.ReadingHistoryPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReadingHistoryServiceTest {

    @Test
    void recentPreservesHistoryOrderEvenWhenRepositoryReturnsDifferentOrder() {
        ReadingHistoryPort port = mock(ReadingHistoryPort.class);
        BookQueryRepository repo = mock(BookQueryRepository.class);
        BookMapper mapper = mock(BookMapper.class);
        ReadingHistoryService service = new ReadingHistoryService(port, repo, mapper);

        BookId firstId = BookId.generate();
        BookId secondId = BookId.generate();
        LocalDateTime firstAt = LocalDateTime.of(2026, 8, 24, 20, 10);
        LocalDateTime secondAt = LocalDateTime.of(2026, 8, 23, 9, 5);
        when(port.recent(12)).thenReturn(List.of(
                new ReadingHistoryPort.Entry(firstId, firstAt),
                new ReadingHistoryPort.Entry(secondId, secondAt)));

        Book first = mock(Book.class);
        Book second = mock(Book.class);
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        when(repo.findByIds(List.of(firstId, secondId))).thenReturn(List.of(second, first));

        BookDto firstDto = BookDto.builder().id(firstId.asString()).title("First").build();
        BookDto secondDto = BookDto.builder().id(secondId.asString()).title("Second").build();
        when(mapper.toDto(first)).thenReturn(firstDto);
        when(mapper.toDto(second)).thenReturn(secondDto);

        var result = service.recent(12);

        assertThat(result).extracting(item -> item.book().getTitle())
                .containsExactly("First", "Second");
        assertThat(result).extracting(item -> item.lastOpenedAt())
                .containsExactly(firstAt, secondAt);
    }

    @Test
    void clearAndRecordAreDelegatedWithoutTouchingProgressRepositories() {
        ReadingHistoryPort port = mock(ReadingHistoryPort.class);
        ReadingHistoryService service = new ReadingHistoryService(
                port, mock(BookQueryRepository.class), mock(BookMapper.class));
        BookId id = BookId.generate();

        service.recordOpened(id);
        service.clear();

        verify(port).recordOpened(id);
        verify(port).clear();
    }
}
