package com.myhomelibcorp.ui.action;

import com.myhomelibcorp.application.action.ActionPreference;
import com.myhomelibcorp.application.action.ActionSettingsService;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.service.MainBookCommandCoordinator;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BookMenuActionContextTest {

    @Test
    void openReaderMenuActionFollowsCanonicalSelectedBook() {
        ApplicationState state = new ApplicationState();
        MainBookCommandCoordinator coordinator = new MainBookCommandCoordinator(state, null, null, null, null, null);
        ActionSettingsService settings = mock(ActionSettingsService.class);
        when(settings.load(anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> new ActionPreference(invocation.getArgument(1), invocation.getArgument(2)));
        ActionRegistry registry = new ActionRegistry(settings);
        MenuItem internal = mock(MenuItem.class);
        MenuItem external = mock(MenuItem.class);

        registry.register(CoreActions.BOOK_OPEN_INTERNAL, internal, coordinator::hasSelectedBook, () -> { });
        registry.register(CoreActions.BOOK_OPEN_EXTERNAL, external, coordinator::hasSelectedBook, () -> { });
        coordinator.selectedBookProperty().addListener((obs, oldBook, newBook) -> registry.refreshContexts());

        verify(internal, atLeastOnce()).setDisable(true);
        verify(external, atLeastOnce()).setDisable(true);
        clearInvocations(internal, external);

        state.getBookDetails().setCurrentBook(BookDto.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .title("Selected")
                .build());
        verify(internal, atLeastOnce()).setDisable(false);
        verify(external, atLeastOnce()).setDisable(false);

        clearInvocations(internal, external);
        state.getBookDetails().setCurrentBook(null);
        verify(internal, atLeastOnce()).setDisable(true);
        verify(external, atLeastOnce()).setDisable(true);
    }
}
