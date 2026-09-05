package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MainBookCommandCoordinatorSelectionTest {

    @Test
    void selectedBookContextTracksCanonicalBookDetailsSelection() {
        ApplicationState state = new ApplicationState();
        MainBookCommandCoordinator coordinator = new MainBookCommandCoordinator(
                state, null, null, null, null, null);
        AtomicInteger changes = new AtomicInteger();
        coordinator.selectedBookProperty().addListener((obs, oldBook, newBook) -> changes.incrementAndGet());

        assertThat(coordinator.hasSelectedBook()).isFalse();

        BookDto selected = BookDto.builder().id("11111111-1111-1111-1111-111111111111").title("Selected").build();
        state.getBookDetails().setCurrentBook(selected);

        assertThat(coordinator.hasSelectedBook()).isTrue();
        assertThat(coordinator.selectedBookProperty().get()).isSameAs(selected);
        assertThat(changes).hasValue(1);

        state.getBookDetails().setCurrentBook(null);
        assertThat(coordinator.hasSelectedBook()).isFalse();
        assertThat(changes).hasValue(2);
    }
}
