package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.collections.ObservableList;

import java.util.List;

public interface NavigationService {
    void navigateToAuthor(AuthorId authorId);
    void navigateToSeries(String seriesName);
    void navigateToGenre(String genreCode);
    void navigateToBook(String bookId);
    void showSearchResults(List<BookDto> books);
    void clearSearch();
    void openBookFile(BookDto book);
    void readBook(BookDto book);
}