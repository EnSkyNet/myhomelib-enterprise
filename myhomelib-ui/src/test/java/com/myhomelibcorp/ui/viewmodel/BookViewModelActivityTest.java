package com.myhomelibcorp.ui.viewmodel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookViewModelActivityTest {
    @Test
    void activitySummaryShowsOnlyNonZeroCountersInStableOrder() {
        BookViewModel vm = new BookViewModel();
        vm.setActivityCounts(2, 3, 4);
        assertThat(vm.getNoteCount()).isEqualTo(2);
        assertThat(vm.getHighlightCount()).isEqualTo(3);
        assertThat(vm.getBookmarkCount()).isEqualTo(4);
        assertThat(vm.activitySummaryProperty().get()).isEqualTo("📝 2  ▰ 3  🔖 4");

        vm.setActivityCounts(0, 5, 0);
        assertThat(vm.activitySummaryProperty().get()).isEqualTo("▰ 5");

        vm.setActivityCounts(0, 0, 0);
        assertThat(vm.activitySummaryProperty().get()).isEmpty();
    }
}
