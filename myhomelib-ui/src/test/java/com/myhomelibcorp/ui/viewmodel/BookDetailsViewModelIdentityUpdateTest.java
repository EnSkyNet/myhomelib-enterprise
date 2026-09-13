package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.BookDto;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BookDetailsViewModelIdentityUpdateTest {

    @Test
    void sameLogicalBookDoesNotRetriggerWorkspaceTransitionAnalysis() {
        BookDetailsViewModel viewModel = new BookDetailsViewModel();
        AtomicInteger changes = new AtomicInteger();
        viewModel.currentBookProperty().addListener((obs, oldValue, newValue) -> changes.incrementAndGet());

        String id = "11111111-1111-1111-1111-111111111111";
        BookDto selected = BookDto.builder().id(id).title("Before").build();
        BookDto sameBookFromReaderLoad = BookDto.builder().id(id).title("After reload").build();

        assertThat(viewModel.setCurrentBookIfDifferentId(selected)).isTrue();
        assertThat(viewModel.setCurrentBookIfDifferentId(sameBookFromReaderLoad)).isFalse();
        assertThat(changes).hasValue(1);
        assertThat(viewModel.getCurrentBook()).isSameAs(selected);
    }

    @Test
    void differentBookStillUpdatesSelection() {
        BookDetailsViewModel viewModel = new BookDetailsViewModel();
        BookDto first = BookDto.builder().id("11111111-1111-1111-1111-111111111111").build();
        BookDto second = BookDto.builder().id("22222222-2222-2222-2222-222222222222").build();

        assertThat(viewModel.setCurrentBookIfDifferentId(first)).isTrue();
        assertThat(viewModel.setCurrentBookIfDifferentId(second)).isTrue();
        assertThat(viewModel.getCurrentBook()).isSameAs(second);
    }

    @Test
    void normalSetterStillRefreshesSameBookMetadata() {
        BookDetailsViewModel viewModel = new BookDetailsViewModel();
        AtomicInteger changes = new AtomicInteger();
        viewModel.currentBookProperty().addListener((obs, oldValue, newValue) -> changes.incrementAndGet());

        String id = "11111111-1111-1111-1111-111111111111";
        viewModel.setCurrentBook(BookDto.builder().id(id).title("Before").build());
        viewModel.setCurrentBook(BookDto.builder().id(id).title("After").build());

        assertThat(changes).hasValue(2);
        assertThat(viewModel.getCurrentBook().getTitle()).isEqualTo("After");
    }
}
