package com.myhomelibcorp.application.usecase.dashboard;

import com.myhomelibcorp.application.dto.ContinueReadingItemDto;
import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.dto.NextInSeriesItemDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.application.usecase.reading.ContinueReadingService;
import com.myhomelibcorp.application.usecase.reading.NextInSeriesService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LoadDashboardDataUseCaseV27Test {

    @Test
    void dashboardFiltersBookAlreadyPresentInContinueReadingFromNextInSeries() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        StatisticsRepository statistics = mock(StatisticsRepository.class);
        AuthorRepository authors = mock(AuthorRepository.class);
        BookMapper bookMapper = mock(BookMapper.class);
        AuthorMapper authorMapper = mock(AuthorMapper.class);
        SessionService session = mock(SessionService.class);
        ContinueReadingService continueReading = mock(ContinueReadingService.class);
        NextInSeriesService nextInSeries = mock(NextInSeriesService.class);
        LibraryStatistics stats = mock(LibraryStatistics.class);

        when(statistics.getStatistics()).thenReturn(stats);
        when(books.findRecent(10)).thenReturn(List.of());
        when(books.findRecentlyAdded(10)).thenReturn(List.of());
        when(authors.findFavorites(10)).thenReturn(List.of());
        when(session.getLastOpenedBookId()).thenReturn(null);
        when(continueReading.recent(10)).thenReturn(List.of(
                new ContinueReadingItemDto("same", "Continue", "", 42, "", LocalDateTime.now(), "desktop")));
        when(nextInSeries.next(10)).thenReturn(List.of(
                new NextInSeriesItemDto("same", "Duplicate", "Series A", 2),
                new NextInSeriesItemDto("next", "Next", "Series B", 3)));

        LoadDashboardDataUseCase useCase = new LoadDashboardDataUseCase(
                books, statistics, authors, bookMapper, authorMapper, new DirectExecutor(), session,
                continueReading, nextInSeries, new LibraryOperationCoordinator());

        var data = useCase.execute().join();

        assertThat(data.getContinueReadingShelf()).extracting(ContinueReadingItemDto::bookId).containsExactly("same");
        assertThat(data.getNextInSeries()).extracting(NextInSeriesItemDto::bookId).containsExactly("next");
        verify(nextInSeries).next(10);
    }

    private static final class DirectExecutor implements ExecutorPort {
        @Override
        public <T> CompletableFuture<T> submit(Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }
}
