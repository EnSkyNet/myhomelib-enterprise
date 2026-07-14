package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.port.out.repository.PageableBookQueryRepository;
import com.myhomelibcorp.application.query.book.PageableBookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.PageRequest;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookTableViewModel;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookLoaderService {

    private final PageableBookQueryRepository pageableRepository;
    private final BookViewModelMapper viewModelMapper;
    private final ApplicationState appState;
    private final UiBackgroundExecutor executor;

    private static final int DEFAULT_PAGE_SIZE = 50;

    public void loadBooks(PageableBookQuery query) {
        BookTableViewModel vm = appState.getBookTable();
        vm.setLoading(true);

        executor.submit(() -> pageableRepository.findPage(query))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    vm.setLoading(false);
                    List<BookViewModel> vms = result.content().stream()
                            .map(viewModelMapper::toViewModel)
                            .collect(Collectors.toList());
                    vm.setBooks(vms);
                    vm.setTotalElements(result.totalElements());
                    vm.setTotalPages(result.totalPages());
                    vm.setCurrentPage(result.currentPage());
                    if (!vms.isEmpty()) {
                        vm.setSelectedBook(vms.get(0));
                    } else {
                        vm.setSelectedBook(null);
                    }
                    appState.getStatusBar().setStatusText(
                            String.format("Показано %d з %d книг", vms.size(), result.totalElements())
                    );
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        vm.setLoading(false);
                        appState.getStatusBar().setStatusText("Помилка завантаження: " + ex.getMessage());
                    });
                    log.error("Failed to load books", ex);
                    return null;
                });
    }

    public void loadBooksByAuthor(AuthorId authorId) {
        PageableBookQuery query = PageableBookQuery.builder()
                .authorId(authorId)
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksBySeries(SeriesId seriesId) {
        PageableBookQuery query = PageableBookQuery.builder()
                .seriesId(seriesId)
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksBySeriesByName(String seriesName) {
        if (seriesName == null || seriesName.isBlank()) {
            loadAllBooks();
            return;
        }
        // Текстовий пошук (як fallback)
        PageableBookQuery query = PageableBookQuery.builder()
                .text(seriesName)
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksByGenre(GenreId genreId) {
        PageableBookQuery query = PageableBookQuery.builder()
                .genreId(genreId)
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksByGroup(GroupId groupId) {
        PageableBookQuery query = PageableBookQuery.builder()
                .groupId(groupId)
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadAllBooks() {
        PageableBookQuery query = PageableBookQuery.builder()
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadRecentBooks() {
        PageableBookQuery query = PageableBookQuery.builder()
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.DATE, SortDirection.DESC))
                .build();
        loadBooks(query);
    }

    public void loadFavoriteBooks() {
        loadBooksByGroup(GroupId.fromLong(1L));
    }

    public void loadContinueReading() {
        PageableBookQuery query = PageableBookQuery.builder()
                .onlyRead(false)
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.DATE, SortDirection.DESC))
                .build();
        loadBooks(query);
    }

    public void loadBooksByLanguage(String languageCode) {
        PageableBookQuery query = PageableBookQuery.builder()
                .language(LanguageCode.of(languageCode))
                .pageRequest(new PageRequest(0, DEFAULT_PAGE_SIZE, SortBy.TITLE, SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksByYear(int year) {
        loadAllBooks();
    }

    public void loadBooksByPublisher(String publisher) {
        loadAllBooks();
    }

    public void nextPage() {
        BookTableViewModel vm = appState.getBookTable();
        if (vm.hasNextPage()) {
            int next = vm.getCurrentPage() + 1;
            PageableBookQuery query = PageableBookQuery.builder()
                    .pageRequest(new PageRequest(next, vm.getPageSize(), vm.getSortBy(), vm.getSortDirection()))
                    .build();
            loadBooks(query);
        }
    }

    public void previousPage() {
        BookTableViewModel vm = appState.getBookTable();
        if (vm.hasPreviousPage()) {
            int prev = vm.getCurrentPage() - 1;
            PageableBookQuery query = PageableBookQuery.builder()
                    .pageRequest(new PageRequest(prev, vm.getPageSize(), vm.getSortBy(), vm.getSortDirection()))
                    .build();
            loadBooks(query);
        }
    }

    public void setPageSize(int size) {
        BookTableViewModel vm = appState.getBookTable();
        vm.setPageSize(size);
        PageableBookQuery query = PageableBookQuery.builder()
                .pageRequest(new PageRequest(0, size, vm.getSortBy(), vm.getSortDirection()))
                .build();
        loadBooks(query);
    }
}