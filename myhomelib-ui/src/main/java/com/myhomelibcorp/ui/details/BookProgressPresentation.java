package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.BookDto;
import java.util.Objects;

/** Reconcile an asynchronously reloaded book with the current visible catalogue selection. */
public final class BookProgressPresentation {
    private BookProgressPresentation() {}

    public static BookDto preserveVisibleProgress(BookDto reloaded, BookDto selected) {
        if (reloaded == null || selected == null || reloaded.getId() == null
                || !Objects.equals(reloaded.getId(), selected.getId())) return reloaded;
        // A catalogue row may reflect fresh reading state while a separately reloaded
        // details DTO still contains the initial zero projection. Never replace a
        // nonzero stored value or copy progress from a different selection.
        if (reloaded.getProgress() != 0 || selected.getProgress() <= 0) return reloaded;
        return reloaded.toBuilder().progress(Math.min(100, selected.getProgress())).build();
    }
}
