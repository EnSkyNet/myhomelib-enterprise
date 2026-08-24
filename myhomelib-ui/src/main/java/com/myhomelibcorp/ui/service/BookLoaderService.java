package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.navigation.ArchiveNavigationKey;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
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

    public void loadBooksByYear(int year) {
        BookQuery query = BookQuery.builder()
                .year(year)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadBooksByArchive(ArchiveNavigationKey archive) {
        if (archive == null) throw new IllegalArgumentException("archive cannot be null");
        BookQuery query = BookQuery.builder()
                .archive(archive.collectionRoot(), archive.archivePath())
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadBooksByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("keyword cannot be blank");
        BookQuery query = BookQuery.builder()
                .keyword(keyword)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadBooksByReviewSubset(ReviewNavigationFilter filter) {
        if (filter == null) throw new IllegalArgumentException("filter cannot be null");
        BookQuery query = BookQuery.builder()
                .onlyRated(filter.onlyRated())
                .onlyReviewed(filter.onlyReviewed())
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadFavoriteBooks() {
        loadBooksByGroup(GroupId.fromLong(1L));
    }

    public void loadAlreadyReadBooks() {
        BookQuery query = BookQuery.builder()
                .onlyRead(true)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .sortBy(SortBy.TITLE)
                .direction(SortDirection.ASC)
                .build();
        loadBooks(query);
    }

    public void loadReadingHistory() {
        BookQuery query = BookQuery.builder()
                .onlyInHistory(true)
                .pagination(Pagination.of(DEFAULT_PAGE_SIZE, 0))
                .build();
        loadBooks(query);
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
        if (vm.hasNextPage()) loadBooks(withPagination(lastQuery, vm.getPageSize(), vm.getCurrentPage() + 1));
    }

    public void previousPage() {
        BookTableViewModel vm = appState.getBookTable();
        if (vm.hasPreviousPage()) loadBooks(withPagination(lastQuery, vm.getPageSize(), vm.getCurrentPage() - 1));
    }

    public void setPageSize(int size) {
        BookTableViewModel vm = appState.getBookTable();
        vm.setPageSize(size);
        loadBooks(withPagination(lastQuery, size, 0));
    }

    private BookQuery withPagination(BookQuery base, int pageSize, int page) {
        if (base == null) base = BookQuery.builder().build();
        return BookQuery.builder()
                .authorId(base.authorId()).seriesId(base.seriesId()).genreId(base.genreId()).groupId(base.groupId())
                .text(base.text()).keyword(base.keyword()).language(base.language()).format(base.format()).year(base.year())
                .archive(base.archiveCollectionRoot(), base.archivePath())
                .pagination(Pagination.of(pageSize, Math.max(0, page) * pageSize))
                .sortBy(base.sortBy()).direction(base.direction())
                .onlyRead(base.onlyRead()).onlyFavorites(base.onlyFavorites())
                .onlyRated(base.onlyRated()).onlyReviewed(base.onlyReviewed())
                .onlyInHistory(base.onlyInHistory())
                .withoutSeries(base.withoutSeries()).withCover(base.withCover())
                .build();
    }
}