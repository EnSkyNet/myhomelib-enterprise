package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.application.usecase.book.LoadBooksUseCase;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.table.BookTableController;
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

    private final LoadBooksUseCase loadBooksUseCase;
    private final BookViewModelMapper viewModelMapper;
    private final ApplicationState appState;
    private final UiBackgroundExecutor executor;

    private static final int DEFAULT_PAGE_SIZE = 50;
    private BookQuery lastQuery;

    // ===== Завантаження книг =====

    public void loadBooks(BookQuery query) {
        this.lastQuery = query;
        BookTableViewModel vm = appState.getBookTable();
        vm.setLoading(true);

        executor.submit(() -> {
            PageResult<BookDto> result = loadBooksUseCase.execute(query);
            log.info("Завантажено {} книг з {}", result.content().size(), result.totalElements());
            return result;
        }).thenAccept(result -> UiExecutor.runOnUiThread(() -> {
            vm.setLoading(false);

            List<BookViewModel> vms = result.content().stream()
                    .map(viewModelMapper::toViewModel)
                    .collect(Collectors.toList());

            BookTableController controller = appState.getBookTableController();
            if (controller != null) {
                controller.loadGroupedBooks(vms);
            } else {
                vm.setBooks(vms);
            }

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
        })).exceptionally(ex -> {
            UiExecutor.runOnUiThread(() -> {
                vm.setLoading(false);
                appState.getStatusBar().setStatusText("Помилка завантаження: " + ex.getMessage());
            });
            log.error("Failed to load books", ex);
            return null;
        });
    }

    public void reloadLastQuery() {
        if (lastQuery != null) {
            loadBooks(lastQuery);
        } else {
            loadAllBooks();
        }
    }

    // ===== Спеціалізовані методи =====

    public void loadBooksByAuthor(AuthorId authorId) {
        BookQuery query = BookQuery.builder()
                .authorId(authorId)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadBooksBySeries(SeriesId seriesId) {
        BookQuery query = BookQuery.builder()
                .seriesId(seriesId)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadBooksByGenre(GenreId genreId) {
        BookQuery query = BookQuery.builder()
                .genreId(genreId)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadBooksByGroup(GroupId groupId) {
        BookQuery query = BookQuery.builder()
                .groupId(groupId)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadAllBooks() {
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadBooksByLanguage(String languageCode) {
        BookQuery query = BookQuery.builder()
                .language(LanguageCode.of(languageCode))
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadFavoriteBooks() {
        loadBooksByGroup(GroupId.fromLong(1L));
    }

    // ===== Dashboard =====

    public List<BookViewModel> loadRecentBooks(int limit) {
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(limit, 0))
                .sortBy(SortBy.DATE)
                .direction(SortDirection.DESC)
                .build();
        PageResult<BookDto> result = loadBooksUseCase.execute(query);
        return result.content().stream()
                .map(viewModelMapper::toViewModel)
                .collect(Collectors.toList());
    }

    public List<BookViewModel> loadRecentlyAdded(int limit) {
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(limit, 0))
                .sortBy(SortBy.DATE)
                .direction(SortDirection.DESC)
                .build();
        PageResult<BookDto> result = loadBooksUseCase.execute(query);
        return result.content().stream()
                .map(viewModelMapper::toViewModel)
                .collect(Collectors.toList());
    }

    // ===== Пагінація =====

    public void nextPage() {
        BookTableViewModel vm = appState.getBookTable();
        if (vm.hasNextPage()) {
            int next = vm.getCurrentPage() + 1;
            BookQuery query = BookQuery.builder()
                    .pagination(Pagination.of(vm.getPageSize(), next * vm.getPageSize()))
                    .sortBy(vm.getSortBy())
                    .direction(vm.getSortDirection())
                    .build();
            loadBooks(query);
        }
    }

    public void previousPage() {
        BookTableViewModel vm = appState.getBookTable();
        if (vm.hasPreviousPage()) {
            int prev = vm.getCurrentPage() - 1;
            BookQuery query = BookQuery.builder()
                    .pagination(Pagination.of(vm.getPageSize(), prev * vm.getPageSize()))
                    .sortBy(vm.getSortBy())
                    .direction(vm.getSortDirection())
                    .build();
            loadBooks(query);
        }
    }

    public void setPageSize(int size) {
        BookTableViewModel vm = appState.getBookTable();
        vm.setPageSize(size);
        BookQuery query = BookQuery.builder()
                .pagination(Pagination.of(size, 0))
                .sortBy(vm.getSortBy())
                .direction(vm.getSortDirection())
                .build();
        loadBooks(query);
    }
}