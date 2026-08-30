package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.navigation.ArchiveNavigationKey;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
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
    void navigateToYear(int year);
    void navigateToLanguage(String languageCode);
    void navigateToArchive(ArchiveNavigationKey archive);
    void navigateToKeyword(String keyword);
    void navigateToGroup(GroupId groupId);
    void navigateToReviews(ReviewNavigationFilter filter);
    void navigateToUpdates();
    void navigateToAlreadyRead();
    void navigateToHistory();
    void navigateToAllBooks();
    void navigateToBook(BookId bookId);
    void showSearchResults(List<BookDto> results);
    void openBookFile(BookDto book);
    void openBookFolder(BookDto book);
    void readBook(BookDto book);
    void navigateToPublisher(String publisherName);

    boolean canGoBack();
    boolean canGoForward();
    void goBack();
    void goForward();
}