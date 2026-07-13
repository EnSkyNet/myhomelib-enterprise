package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class AuthorWorkspaceViewModel {

    private final ObjectProperty<AuthorDto> selectedAuthor = new SimpleObjectProperty<>();
    private final ObservableList<BookDto> books = FXCollections.observableArrayList();

    public ObjectProperty<AuthorDto> selectedAuthorProperty() {
        return selectedAuthor;
    }

    public ObservableList<BookDto> getBooks() {
        return books;
    }

    public void setSelectedAuthor(AuthorDto author) {
        selectedAuthor.set(author);
    }

    public AuthorDto getSelectedAuthor() {
        return selectedAuthor.get();
    }

    public void setBooks(java.util.List<BookDto> bookList) {
        books.setAll(bookList);
    }

    public void clear() {
        selectedAuthor.set(null);
        books.clear();
    }
}