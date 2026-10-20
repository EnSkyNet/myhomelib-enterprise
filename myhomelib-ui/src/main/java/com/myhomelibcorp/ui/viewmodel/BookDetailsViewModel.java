package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

public class BookDetailsViewModel {

    private final ObjectProperty<BookDto> currentBook = new SimpleObjectProperty<>();
    private final IntegerProperty readingProgress = new SimpleIntegerProperty(0);

    public IntegerProperty readingProgressProperty() {
        return readingProgress;
    }

    public void updateReadingProgress(String bookId, int progress) {
        BookDto selected = currentBook.get();
        if (selected == null || bookId == null || !bookId.equals(selected.getId())) return;
        int normalized = Math.max(0, Math.min(100, progress));
        selected.setProgress(normalized);
        readingProgress.set(normalized);
    }

    public ObjectProperty<BookDto> currentBookProperty() {
        return currentBook;
    }

    public void setCurrentBook(BookDto book) {
        currentBook.set(book);
        readingProgress.set(book == null ? 0 : book.getProgress());
    }

    /**
     * Updates the selected book only when its logical identity changes. This is useful for
     * workspace transitions (for example Book -> Reader) where the same book remains selected:
     * firing the property again would restart the expensive rich-details analysis and parse the
     * book a second time. Normal metadata refreshes must keep using {@link #setCurrentBook(BookDto)}
     * so a new DTO for the same id can still refresh the details panel.
     *
     * @return {@code true} when the property was changed.
     */
    public boolean setCurrentBookIfDifferentId(BookDto book) {
        BookDto current = currentBook.get();
        if (sameBookId(current, book)) {
            // Keep the selected DTO to avoid an expensive rich-details reload.
            // Propagate the latest nonzero progress for the same logical book.
            if (current != null && book != null && book.getProgress() > 0
                    && book.getProgress() != current.getProgress()) {
                updateReadingProgress(current.getId(), book.getProgress());
            }
            return false;
        }
        setCurrentBook(book);
        return true;
    }

    private static boolean sameBookId(BookDto left, BookDto right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        String leftId = left.getId();
        String rightId = right.getId();
        return leftId != null && !leftId.isBlank() && leftId.equals(rightId);
    }

    public BookDto getCurrentBook() {
        return currentBook.get();
    }

    public void clear() {
        setCurrentBook(null);
    }
}