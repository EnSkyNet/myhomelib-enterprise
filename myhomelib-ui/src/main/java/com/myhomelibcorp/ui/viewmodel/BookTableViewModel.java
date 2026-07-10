package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class BookTableViewModel {

    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();
    private final ObjectProperty<BookViewModel> selectedBook = new SimpleObjectProperty<>();

    private final IntegerProperty currentPage = new SimpleIntegerProperty(0);
    private final IntegerProperty pageSize = new SimpleIntegerProperty(200);
    private final LongProperty totalElements = new SimpleLongProperty(0);
    private final IntegerProperty totalPages = new SimpleIntegerProperty(0);

    private final ObjectProperty<SortBy> sortBy = new SimpleObjectProperty<>(SortBy.TITLE);
    private final ObjectProperty<SortDirection> sortDirection = new SimpleObjectProperty<>(SortDirection.ASC);

    private final BooleanProperty loading = new SimpleBooleanProperty(false);

    public ObservableList<BookViewModel> getBooks() {
        return books;
    }

    public ObjectProperty<BookViewModel> selectedBookProperty() {
        return selectedBook;
    }

    public IntegerProperty currentPageProperty() {
        return currentPage;
    }

    public IntegerProperty pageSizeProperty() {
        return pageSize;
    }

    public LongProperty totalElementsProperty() {
        return totalElements;
    }

    public IntegerProperty totalPagesProperty() {
        return totalPages;
    }

    public ObjectProperty<SortBy> sortByProperty() {
        return sortBy;
    }

    public ObjectProperty<SortDirection> sortDirectionProperty() {
        return sortDirection;
    }

    public BooleanProperty loadingProperty() {
        return loading;
    }

    public void setBooks(java.util.List<BookViewModel> list) {
        books.setAll(list);
    }

    public void setSelectedBook(BookViewModel book) {
        selectedBook.set(book);
    }

    public void setCurrentPage(int page) {
        currentPage.set(page);
    }

    public void setPageSize(int size) {
        pageSize.set(size);
    }

    public void setTotalElements(long total) {
        totalElements.set(total);
    }

    public void setTotalPages(int pages) {
        totalPages.set(pages);
    }

    public void setSortBy(SortBy sort) {
        sortBy.set(sort);
    }

    public void setSortDirection(SortDirection dir) {
        sortDirection.set(dir);
    }

    public void setLoading(boolean loading) {
        this.loading.set(loading);
    }

    public void clear() {
        books.clear();
        selectedBook.set(null);
    }

    public boolean hasNextPage() {
        return currentPage.get() < totalPages.get() - 1;
    }

    public boolean hasPreviousPage() {
        return currentPage.get() > 0;
    }

    public BookViewModel getSelectedBook() {
        return selectedBook.get();
    }
}