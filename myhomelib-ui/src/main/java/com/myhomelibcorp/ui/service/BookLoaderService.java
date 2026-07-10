package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.port.out.repository.PageableBookQueryRepository;
import com.myhomelibcorp.application.query.book.PageableBookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;  // <-- ВИПРАВЛЕНО
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

    private static final int DEFAULT_PAGE_SIZE = 200;

    public void loadBooks(PageableBookQuery query) {
        BookTableViewModel vm = appState.getBookTable();
        vm.setLoading(true);

        try {
            PageResult<BookListItem> result = pageableRepository.findPage(query);
            List<BookViewModel> vms = result.content().stream()
                    .map(viewModelMapper::toViewModel)
                    .collect(Collectors.toList());
            UiExecutor.runOnUiThread(() -> {
                vm.setLoading(false);
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
            });
        } catch (Exception e) {
            UiExecutor.runOnUiThread(() -> {
                vm.setLoading(false);
                appState.getStatusBar().setStatusText("Помилка завантаження: " + e.getMessage());
            });
            log.error("Failed to load books", e);
        }
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
}