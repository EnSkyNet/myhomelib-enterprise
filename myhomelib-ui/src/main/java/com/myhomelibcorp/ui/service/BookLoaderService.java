package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.navigation.ArchiveNavigationKey;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.application.usecase.book.LoadBooksUseCase;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookLoaderService {

    private final LoadBooksUseCase loadBooksUseCase;
    private final BookViewModelMapper viewModelMapper;
    private final ApplicationState appState;
    private final UiBackgroundExecutor executor;
    private final BookFilterStateService filterStateService;

    private static final int DEFAULT_PAGE_SIZE = 50;
    private final AtomicLong requestGeneration = new AtomicLong();
    private BookQuery lastQuery;

    // ===== Завантаження книг =====

    public void loadBooks(BookQuery query) {
        BookQuery effectiveQuery = withFilter(query, filterStateService.current());
        BookTableController activeController = appState.getBookTableController();
        if (activeController != null && !effectiveQuery.onlyInHistory()) {
            effectiveQuery = activeController.applyPreferredSort(effectiveQuery);
        }
        this.lastQuery = effectiveQuery;
        long requestId = requestGeneration.incrementAndGet();
        String collectionId = currentCollectionId();
        BookTableViewModel vm = appState.getBookTable();
        vm.setLoading(true);
        BookQuery submittedQuery = effectiveQuery;

        executor.submit(() -> {
            PageResult<BookDto> result = loadBooksUseCase.execute(submittedQuery);
            log.info("Завантажено {} книг з {}", result.content().size(), result.totalElements());
            return result;
        }).thenAccept(result -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrent(requestId, collectionId)) {
                log.debug("Ігноруємо застарілий результат BookLoader request={} collection={}", requestId, collectionId);
                return;
            }
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

            // Loading a page must not implicitly arm single-book commands for the first row.
            // Row selection (current book) and checkbox selection (batch books) are intentionally separate.
            vm.setSelectedBook(null);
            appState.getBookDetails().setCurrentBook(null);
            appState.getStatusBar().setStatusText(
                    String.format("Показано %d з %d книг", vms.size(), result.totalElements())
            );
        })).exceptionally(ex -> {
            UiExecutor.runOnUiThread(() -> {
                if (!isCurrent(requestId, collectionId)) return;
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

    public void loadBooksByPublisher(String publisher) {
        if (publisher == null || publisher.isBlank()) return;
        BookQuery query = BookQuery.builder()
                .publisher(publisher)
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
                .text(base.text()).keyword(base.keyword()).publisher(base.publisher()).language(base.language()).format(base.format()).year(base.year())
                .archive(base.archiveCollectionRoot(), base.archivePath())
                .pagination(Pagination.of(pageSize, Math.max(0, page) * pageSize))
                .sortBy(base.sortBy()).direction(base.direction())
                .onlyRead(base.onlyRead()).onlyFavorites(base.onlyFavorites())
                .onlyRated(base.onlyRated()).onlyReviewed(base.onlyReviewed())
                .onlyInHistory(base.onlyInHistory())
                .withoutSeries(base.withoutSeries()).withCover(base.withCover())
                .filterSpec(base.filterSpec())
                .build();
    }

    private BookQuery withFilter(BookQuery base, BookFilterSpec filter) {
        if (base == null) base = BookQuery.builder().build();
        return BookQuery.builder()
                .authorId(base.authorId()).seriesId(base.seriesId()).genreId(base.genreId()).groupId(base.groupId())
                .text(base.text()).keyword(base.keyword()).publisher(base.publisher()).language(base.language()).format(base.format()).year(base.year())
                .archive(base.archiveCollectionRoot(), base.archivePath())
                .pagination(base.pagination()).sortBy(base.sortBy()).direction(base.direction())
                .onlyRead(base.onlyRead()).onlyFavorites(base.onlyFavorites())
                .onlyRated(base.onlyRated()).onlyReviewed(base.onlyReviewed())
                .onlyInHistory(base.onlyInHistory())
                .withoutSeries(base.withoutSeries()).withCover(base.withCover())
                .filterSpec(filter)
                .build();
    }

    public void setSort(SortBy sortBy, SortDirection direction) {
        if (lastQuery == null) return;
        BookQuery sorted = BookQuery.builder()
                .authorId(lastQuery.authorId()).seriesId(lastQuery.seriesId()).genreId(lastQuery.genreId()).groupId(lastQuery.groupId())
                .text(lastQuery.text()).keyword(lastQuery.keyword()).publisher(lastQuery.publisher()).language(lastQuery.language()).format(lastQuery.format()).year(lastQuery.year())
                .archive(lastQuery.archiveCollectionRoot(), lastQuery.archivePath())
                .pagination(Pagination.of(lastQuery.pagination().limit(), 0))
                .sortBy(sortBy == null ? SortBy.TITLE : sortBy)
                .direction(direction == null ? SortDirection.ASC : direction)
                .onlyRead(lastQuery.onlyRead()).onlyFavorites(lastQuery.onlyFavorites())
                .onlyRated(lastQuery.onlyRated()).onlyReviewed(lastQuery.onlyReviewed())
                .onlyInHistory(lastQuery.onlyInHistory())
                .withoutSeries(lastQuery.withoutSeries()).withCover(lastQuery.withCover())
                .filterSpec(filterStateService.current())
                .build();
        loadBooks(sorted);
    }

    /** Invalidates all in-flight page loads; useful before destructive workspace/collection changes. */
    public void invalidatePendingRequests() {
        requestGeneration.incrementAndGet();
    }

    private boolean isCurrent(long requestId, String collectionId) {
        return requestId == requestGeneration.get() && Objects.equals(collectionId, currentCollectionId());
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }

    public BookQuery getLastQuery() { return lastQuery; }
}
