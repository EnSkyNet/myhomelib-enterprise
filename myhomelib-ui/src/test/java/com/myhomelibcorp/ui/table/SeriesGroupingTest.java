package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeriesGroupingTest {

    @Test
    void groupingNeverReordersFilteredSortedBooks() {
        BookViewModel first = book("3", "Gamma", "Series B");
        BookViewModel second = book("2", "Beta", "Series B");
        BookViewModel third = book("1", "Alpha", "Series A");

        var rows = SeriesGrouping.groupPreservingOrder(List.of(first, second, third));
        var booksOnly = rows.stream().filter(row -> !row.isGroupHeader()).toList();

        assertThat(booksOnly).containsExactly(first, second, third);
        assertThat(rows.stream().filter(BookViewModel::isGroupHeader).map(BookViewModel::getSeries).toList())
                .containsExactly("Series B", "Series A");
    }

    @Test
    void blankSeriesDoesNotCreateHeader() {
        BookViewModel a = book("1", "A", null);
        BookViewModel b = book("2", "B", "  ");
        assertThat(SeriesGrouping.groupPreservingOrder(List.of(a, b)))
                .containsExactly(a, b);
    }

    private BookViewModel book(String id, String title, String series) {
        BookViewModel vm = new BookViewModel();
        vm.setId(id);
        vm.setTitle(title);
        vm.setSeries(series);
        return vm;
    }
}
