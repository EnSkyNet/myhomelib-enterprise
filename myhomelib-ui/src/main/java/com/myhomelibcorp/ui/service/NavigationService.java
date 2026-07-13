package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;

import java.util.List;

public interface NavigationService {
    void navigateToAuthor(AuthorId authorId);
    void navigateToSeries(SeriesId seriesId);
    void navigateToSeriesByName(String seriesName);
    void navigateToGenre(GenreId genreId);
    void navigateToBook(BookId bookId);
    void navigateToCollection(GroupId groupId);
    void showSearchResults(List<BookDto> results);
    void clearSearch();
    void openBookFile(BookDto book);
    void openBookFolder(BookDto book);
    void readBook(BookDto book);

    // Навігація назад/вперед
    boolean canGoBack();
    boolean canGoForward();
    void goBack();
    void goForward();
}