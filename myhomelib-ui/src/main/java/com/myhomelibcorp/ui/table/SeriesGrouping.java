package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.ui.viewmodel.BookViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure presentation helper that inserts visual series headers without reordering
 * the already filtered/sorted SQL page.
 */
public final class SeriesGrouping {
    private SeriesGrouping() { }

    public static List<BookViewModel> groupPreservingOrder(List<BookViewModel> books) {
        if (books == null || books.isEmpty()) return List.of();

        List<BookViewModel> rows = new ArrayList<>(books.size() + 8);
        String currentSeries = null;
        for (BookViewModel book : books) {
            if (book == null) continue;
            String series = normalize(book.getSeries());
            if (series != null && !series.equals(currentSeries)) {
                BookViewModel header = new BookViewModel();
                header.setTitle("Серія: " + series);
                header.setSeries(series);
                header.setGroupHeader(true);
                rows.add(header);
            }
            currentSeries = series;
            rows.add(book);
        }
        return rows;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
