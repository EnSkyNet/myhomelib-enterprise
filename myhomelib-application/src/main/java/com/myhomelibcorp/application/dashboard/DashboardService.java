package com.myhomelibcorp.application.dashboard;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.DashboardData;
import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.SessionRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final BookQueryRepository bookQueryRepository;
    private final StatisticsRepository statisticsRepository;
    private final SessionRepository sessionRepository;
    private final AuthorRepository authorRepository;
    private final BookMapper bookMapper;
    private final AuthorMapper authorMapper;

    public CompletableFuture<DashboardData> loadDashboardData() {
        CompletableFuture<LibraryStatistics> statsFuture = CompletableFuture.supplyAsync(
                statisticsRepository::getStatistics);

        CompletableFuture<List<BookDto>> recentFuture = CompletableFuture.supplyAsync(() ->
                bookQueryRepository.findRecent(10).stream()
                        .map(bookMapper::toDto)
                        .collect(Collectors.toList()));

        CompletableFuture<List<BookDto>> addedFuture = CompletableFuture.supplyAsync(() ->
                bookQueryRepository.findRecentlyAdded(10).stream()
                        .map(bookMapper::toDto)
                        .collect(Collectors.toList()));

        CompletableFuture<List<AuthorDto>> favAuthorsFuture = CompletableFuture.supplyAsync(() ->
                authorRepository.findFavorites(10).stream()
                        .map(authorMapper::toDto)
                        .collect(Collectors.toList()));

        CompletableFuture<BookDto> continueFuture = CompletableFuture.supplyAsync(() -> {
            Long lastBookId = sessionRepository.getLastOpenedBookId();
            if (lastBookId == null) return null;
            Book book = bookQueryRepository.findById(BookId.fromLong(lastBookId)).orElse(null);
            return book != null ? bookMapper.toDto(book) : null;
        });

        return CompletableFuture.allOf(statsFuture, recentFuture, addedFuture, favAuthorsFuture, continueFuture)
                .thenApply(v -> DashboardData.builder()
                        .statistics(statsFuture.join())
                        .recentBooks(recentFuture.join())
                        .recentAdded(addedFuture.join())
                        .favoriteAuthors(favAuthorsFuture.join())
                        .continueReading(continueFuture.join())
                        .build());
    }
}