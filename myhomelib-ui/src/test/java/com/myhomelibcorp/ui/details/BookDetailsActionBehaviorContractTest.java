package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.ClassicLibraryActionsService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookDetailsViewModel;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookDetailsActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void onRead_onOpenFolder_and_onEditDelegateForCurrentBook() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(BookDetailsController.class);
        BookDetailsController controller = fixture.instance();
        ApplicationState state = fixture.dependency(ApplicationState.class);
        NavigationService navigation = fixture.dependency(NavigationService.class);
        ClassicLibraryActionsService editor = fixture.dependency(ClassicLibraryActionsService.class);
        BookDto book = BookDto.builder().id("11111111-1111-1111-1111-111111111111").title("Book").build();
        BookDetailsViewModel details = new BookDetailsViewModel();
        details.setCurrentBook(book);
        when(state.getBookDetails()).thenReturn(details);

        UiActionTestSupport.invoke(controller, "onRead");
        verify(navigation).readBook(book);
        UiActionTestSupport.invoke(controller, "onOpenFolder");
        verify(navigation).openBookFolder(book);

        assertThat(editor).as("details Edit action must reuse the canonical book editor").isNotNull();
        UiActionTestSupport.invoke(controller, "onEdit");
        verify(editor).editBook(isNull(Window.class), eq(BookId.fromString("11111111-1111-1111-1111-111111111111")), any(Runnable.class));
    }

    @Test
    void onShowImagesIsSafeWhenNoRichInspectionIsLoaded() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(BookDetailsController.class);
        long dialogsBefore = UiActionTestSupport.fx(() -> Window.getWindows().stream().filter(Window::isShowing).count());
        UiActionTestSupport.invoke(fixture.instance(), "onShowImages");
        long dialogsAfter = UiActionTestSupport.fx(() -> Window.getWindows().stream().filter(Window::isShowing).count());
        assertThat(dialogsAfter).isEqualTo(dialogsBefore);
    }
}
