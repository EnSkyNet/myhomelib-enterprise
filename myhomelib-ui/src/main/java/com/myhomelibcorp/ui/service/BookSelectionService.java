package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.springframework.stereotype.Service;

@Service
public class BookSelectionService {
    private final ObjectProperty<BookViewModel> selectedBook = new SimpleObjectProperty<>();
    public ObjectProperty<BookViewModel> selectedBookProperty() { return selectedBook; }
    public void selectBook(BookViewModel book) { selectedBook.set(book); }
    public void clearSelection() { selectedBook.set(null); }
}