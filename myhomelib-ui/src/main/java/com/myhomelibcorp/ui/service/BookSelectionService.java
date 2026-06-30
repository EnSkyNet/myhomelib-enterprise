package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.springframework.stereotype.Service;

@Service
public class BookSelectionService {

    private final ObjectProperty<BookDto> selectedBook = new SimpleObjectProperty<>();

    public ObjectProperty<BookDto> selectedBookProperty() {
        return selectedBook;
    }

    public BookDto getSelectedBook() {
        return selectedBook.get();
    }

    public void selectBook(BookDto book) {
        selectedBook.set(book);
    }

    public void clearSelection() {
        selectedBook.set(null);
    }
}