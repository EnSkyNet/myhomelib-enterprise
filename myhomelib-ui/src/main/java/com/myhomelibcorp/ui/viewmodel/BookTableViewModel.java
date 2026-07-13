package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class BookTableViewModel {

    // Список книг для відображення в таблиці
    private final ObservableList<BookViewModel> books = FXCollections.observableArrayList();

    // Поточна вибрана книга
    private final ObjectProperty<BookViewModel> selectedBook = new SimpleObjectProperty<>();

    // Пагінація
    private final IntegerProperty currentPage = new SimpleIntegerProperty(0);
    private final IntegerProperty pageSize = new SimpleIntegerProperty(50);
    private final LongProperty totalElements = new SimpleLongProperty(0);
    private final IntegerProperty totalPages = new SimpleIntegerProperty(0);

    // Сортування
    private final ObjectProperty<SortBy> sortBy = new SimpleObjectProperty<>(SortBy.TITLE);
    private final ObjectProperty<SortDirection> sortDirection = new SimpleObjectProperty<>(SortDirection.ASC);

    // Стан завантаження
    private final BooleanProperty loading = new SimpleBooleanProperty(false);

    // Повідомлення про статус
    private final StringProperty statusMessage = new SimpleStringProperty("");

    // ===================== ГЕТТЕРИ ВЛАСТИВОСТЕЙ =====================

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

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    // ===================== ГЕТТЕРИ ТА СЕТТЕРИ =====================

    public void setBooks(java.util.List<BookViewModel> list) {
        books.setAll(list);
    }

    public void setSelectedBook(BookViewModel book) {
        selectedBook.set(book);
    }

    public BookViewModel getSelectedBook() {
        return selectedBook.get();
    }

    public void setCurrentPage(int page) {
        currentPage.set(page);
    }

    public int getCurrentPage() {
        return currentPage.get();
    }

    public void setPageSize(int size) {
        pageSize.set(size);
    }

    public int getPageSize() {
        return pageSize.get();
    }

    public void setTotalElements(long total) {
        totalElements.set(total);
    }

    public long getTotalElements() {
        return totalElements.get();
    }

    public void setTotalPages(int pages) {
        totalPages.set(pages);
    }

    public int getTotalPages() {
        return totalPages.get();
    }

    public void setSortBy(SortBy sort) {
        sortBy.set(sort);
    }

    public SortBy getSortBy() {
        return sortBy.get();
    }

    public void setSortDirection(SortDirection dir) {
        sortDirection.set(dir);
    }

    public SortDirection getSortDirection() {
        return sortDirection.get();
    }

    public void setLoading(boolean loading) {
        this.loading.set(loading);
    }

    public boolean isLoading() {
        return loading.get();
    }

    public void setStatusMessage(String message) {
        statusMessage.set(message);
    }

    public String getStatusMessage() {
        return statusMessage.get();
    }

    // ===================== ДОПОМІЖНІ МЕТОДИ =====================

    /**
     * Перевіряє, чи є наступна сторінка
     */
    public boolean hasNextPage() {
        return currentPage.get() < totalPages.get() - 1;
    }

    /**
     * Перевіряє, чи є попередня сторінка
     */
    public boolean hasPreviousPage() {
        return currentPage.get() > 0;
    }

    /**
     * Очищує всі дані
     */
    public void clear() {
        books.clear();
        selectedBook.set(null);
        currentPage.set(0);
        totalElements.set(0);
        totalPages.set(0);
        loading.set(false);
        statusMessage.set("");
    }

    /**
     * Оновлює статус з інформацією про кількість книг
     */
    public void updateStatus() {
        int size = books.size();
        long total = totalElements.get();
        if (size == 0) {
            statusMessage.set("Немає книг для відображення");
        } else {
            statusMessage.set(String.format("Показано %d з %d книг", size, total));
        }
    }

    @Override
    public String toString() {
        return "BookTableViewModel{" +
                "booksCount=" + books.size() +
                ", currentPage=" + currentPage.get() +
                ", pageSize=" + pageSize.get() +
                ", totalElements=" + totalElements.get() +
                ", totalPages=" + totalPages.get() +
                '}';
    }
}