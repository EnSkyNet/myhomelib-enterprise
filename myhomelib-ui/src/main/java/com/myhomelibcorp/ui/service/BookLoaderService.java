package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.port.out.repository.PageableBookQueryRepository;
import com.myhomelibcorp.application.query.book.PageableBookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
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
    private final UiBackgroundExecutor executor; // Виправлено

    private static final int DEFAULT_PAGE_SIZE = 200;

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
                .pageRequest(new com.myhomelibcorp.application.query.common.PageRequest(
                        0, DEFAULT_PAGE_SIZE,
                        com.myhomelibcorp.application.query.common.SortBy.TITLE,
                        com.myhomelibcorp.application.query.common.SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksBySeries(SeriesId seriesId) {
        PageableBookQuery query = PageableBookQuery.builder()
                .seriesId(seriesId)
                .pageRequest(new com.myhomelibcorp.application.query.common.PageRequest(
                        0, DEFAULT_PAGE_SIZE,
                        com.myhomelibcorp.application.query.common.SortBy.TITLE,
                        com.myhomelibcorp.application.query.common.SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksByGenre(GenreId genreId) {
        PageableBookQuery query = PageableBookQuery.builder()
                .genreId(genreId)
                .pageRequest(new com.myhomelibcorp.application.query.common.PageRequest(
                        0, DEFAULT_PAGE_SIZE,
                        com.myhomelibcorp.application.query.common.SortBy.TITLE,
                        com.myhomelibcorp.application.query.common.SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksByGroup(GroupId groupId) {
        PageableBookQuery query = PageableBookQuery.builder()
                .groupId(groupId)
                .pageRequest(new com.myhomelibcorp.application.query.common.PageRequest(
                        0, DEFAULT_PAGE_SIZE,
                        com.myhomelibcorp.application.query.common.SortBy.TITLE,
                        com.myhomelibcorp.application.query.common.SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadAllBooks() {
        PageableBookQuery query = PageableBookQuery.builder()
                .pageRequest(new com.myhomelibcorp.application.query.common.PageRequest(
                        0, DEFAULT_PAGE_SIZE,
                        com.myhomelibcorp.application.query.common.SortBy.TITLE,
                        com.myhomelibcorp.application.query.common.SortDirection.ASC))
                .build();
        loadBooks(query);
    }
    public void loadRecentBooks() {
        PageableBookQuery query = PageableBookQuery.builder()
                .pageRequest(new com.myhomelibcorp.application.query.common.PageRequest(
                        0, DEFAULT_PAGE_SIZE,
                        com.myhomelibcorp.application.query.common.SortBy.DATE,
                        com.myhomelibcorp.application.query.common.SortDirection.DESC))
                .build();
        loadBooks(query);
    }

    public void loadFavoriteBooks() {
        // Для групи "Обране" (група з id = 1)
        GroupId groupId = GroupId.fromLong(1L);
        loadBooksByGroup(groupId);
    }

    public void loadContinueReading() {
        // Завантажити книгу з останнім прогресом
        PageableBookQuery query = PageableBookQuery.builder()
                .onlyRead(false)
                .pageRequest(new com.myhomelibcorp.application.query.common.PageRequest(
                        0, 1,
                        com.myhomelibcorp.application.query.common.SortBy.DATE,
                        com.myhomelibcorp.application.query.common.SortDirection.DESC))
                .build();
        loadBooks(query);
    }

    public void loadBooksByLanguage(String language) {
        PageableBookQuery query = PageableBookQuery.builder()
                .language(com.myhomelibcorp.domain.model.valueobject.LanguageCode.of(language))
                .pageRequest(new com.myhomelibcorp.application.query.common.PageRequest(
                        0, DEFAULT_PAGE_SIZE,
                        com.myhomelibcorp.application.query.common.SortBy.TITLE,
                        com.myhomelibcorp.application.query.common.SortDirection.ASC))
                .build();
        loadBooks(query);
    }

    public void loadBooksByYear(int year) {
        // Потрібно додати фільтр за роком у PageableBookQuery
        // Для простоти поки що завантажуємо всі книги
        loadAllBooks();
    }

    public void loadBooksByPublisher(String publisher) {
        // Потрібно додати фільтр за видавництвом
        // Для простоти поки що завантажуємо всі книги
        loadAllBooks();
    }
}