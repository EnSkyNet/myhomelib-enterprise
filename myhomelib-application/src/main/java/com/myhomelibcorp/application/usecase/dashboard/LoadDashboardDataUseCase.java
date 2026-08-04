package com.myhomelibcorp.application.usecase.dashboard;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.DashboardData;
import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.SessionRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoadDashboardDataUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final StatisticsRepository statisticsRepository;
    private final SessionRepository sessionRepository;
    private final AuthorRepository authorRepository;
    private final BookMapper bookMapper;
    private final AuthorMapper authorMapper;
    private final ExecutorPort executorPort;
    private final SessionService sessionService; // Додаємо

    public CompletableFuture<DashboardData> execute() {
        CompletableFuture<LibraryStatistics> statsFuture = executorPort.submit(
                statisticsRepository::getStatistics);

        CompletableFuture<List<BookDto>> recentFuture = executorPort.submit(() ->
                bookQueryRepository.findRecent(10).stream()
                        .map(bookMapper::toDto)
                        .collect(Collectors.toList()));

        CompletableFuture<List<BookDto>> addedFuture = executorPort.submit(() ->
                bookQueryRepository.findRecentlyAdded(10).stream()
                        .map(bookMapper::toDto)
                        .collect(Collectors.toList()));

        CompletableFuture<List<AuthorDto>> favAuthorsFuture = executorPort.submit(() ->
                authorRepository.findFavorites(10).stream()
                        .map(authorMapper::toDto)
                        .collect(Collectors.toList()));

        CompletableFuture<BookDto> continueFuture = executorPort.submit(() -> {
            // Використовуємо SessionService замість прямого виклику репозиторію
            String lastBookId = sessionService.getLastOpenedBookId();
            if (lastBookId == null || lastBookId.isEmpty()) {
                log.debug("Немає збереженої останньої книги");
                return null;
            }
            Book book = bookQueryRepository.findById(BookId.fromString(lastBookId)).orElse(null);
            if (book == null) {
                log.warn("Останню книгу {} не знайдено в БД", lastBookId);
                return null;
            }
            return bookMapper.toDto(book);
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