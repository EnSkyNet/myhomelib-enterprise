package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

public class BookDetailsViewModel {

    private final ObjectProperty<BookDto> currentBook = new SimpleObjectProperty<>();

    public ObjectProperty<BookDto> currentBookProperty() {
        return currentBook;
    }

    public void setCurrentBook(BookDto book) {
        currentBook.set(book);
    }

    public BookDto getCurrentBook() {
        return currentBook.get();
    }

    public void clear() {
        currentBook.set(null);
    }
}