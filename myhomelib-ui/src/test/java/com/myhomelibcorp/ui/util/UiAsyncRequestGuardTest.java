package com.myhomelibcorp.ui.util;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiAsyncRequestGuardTest {

    @Test
    void rejectsResultAfterCollectionSwitch() {
        ApplicationState state = new ApplicationState();
        state.setCurrentLibraryCollection(collection("first"));
        AtomicLong generation = new AtomicLong();

        UiAsyncRequestToken request = UiAsyncRequestGuard.next(generation, state);
        assertTrue(UiAsyncRequestGuard.isCurrent(request, generation, state));

        state.setCurrentLibraryCollection(collection("second"));
        assertFalse(UiAsyncRequestGuard.isCurrent(request, generation, state));
    }

    @Test
    void rejectsOlderRequestWithinSameCollection() {
        ApplicationState state = new ApplicationState();
        state.setCurrentLibraryCollection(collection("first"));
        AtomicLong generation = new AtomicLong();

        UiAsyncRequestToken older = UiAsyncRequestGuard.next(generation, state);
        UiAsyncRequestToken newer = UiAsyncRequestGuard.next(generation, state);

        assertFalse(UiAsyncRequestGuard.isCurrent(older, generation, state));
        assertTrue(UiAsyncRequestGuard.isCurrent(newer, generation, state));
    }

    private static Collection collection(String id) {
        return new Collection(id, id, Path.of("."), "library.db", 0, null, null, null, null);
    }
}
