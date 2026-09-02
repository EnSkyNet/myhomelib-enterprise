package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStateCollectionSwitchTest {

    @Test
    void changingCollectionIdentityClearsCollectionScopedUiState() {
        ApplicationState state = new ApplicationState();
        Collection first = new Collection("a", "A", Path.of("a"), "a.db", 0, null, null, null, null);
        Collection second = new Collection("b", "B", Path.of("b"), "b.db", 0, null, null, null, null);

        state.setCurrentLibraryCollection(first);
        state.getBookTable().setTotalElements(42);
        state.getStatusBar().setStatistics(LibraryStatistics.builder().booksCount(42).build());

        state.setCurrentLibraryCollection(second);

        assertThat(state.getBookTable().getTotalElements()).isZero();
        assertThat(state.getBookDetails().getCurrentBook()).isNull();
        assertThat(state.getDashboard().getStatistics()).isNull();
        assertThat(state.getStatusBar().statisticsProperty().get()).isNull();
    }

    @Test
    void metadataRefreshForSameCollectionDoesNotClearVisibleState() {
        ApplicationState state = new ApplicationState();
        Collection original = new Collection("same", "Old", Path.of("a"), "a.db", 0, null, null, null, null);
        Collection renamed = new Collection("same", "New", Path.of("a"), "a.db", 0, null, null, null, null);

        state.setCurrentLibraryCollection(original);
        state.getBookTable().setTotalElements(7);
        state.setCurrentLibraryCollection(renamed);

        assertThat(state.getBookTable().getTotalElements()).isEqualTo(7);
        assertThat(state.getCurrentLibraryCollection().getName()).isEqualTo("New");
    }
}
