package com.myhomelibcorp.ui.readinglist;

import com.myhomelibcorp.application.readinglist.AddBooksToReadingListUseCase;
import com.myhomelibcorp.application.readinglist.ListReadingListsUseCase;
import com.myhomelibcorp.application.readinglist.ReadingListDto;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReadingListBookActionUiServiceTest {
    private static final BookId B1 = BookId.fromString("00000000-0000-0000-0000-000000000001");
    private static final BookId B2 = BookId.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void ReadingListBookActionUiService_itemAddsCurrentCheckboxSelectionWhenContextBookIsSelected() throws Exception {
        ListReadingListsUseCase lists = mock(ListReadingListsUseCase.class);
        AddBooksToReadingListUseCase add = mock(AddBooksToReadingListUseCase.class);
        DialogService dialogs = mock(DialogService.class);
        ApplicationState state = new ApplicationState();
        state.setCurrentLibraryCollection(new Collection("c1", "Test", Path.of("."), null, 1, null, null, null, null));
        BookSelectionService selection = new BookSelectionService(state);
        selection.setSelectedIds(List.of(B1, B2), true);
        ReadingListDto list = new ReadingListDto("list-1", "Queue", "", 0, LocalDateTime.now(), LocalDateTime.now());
        when(lists.execute()).thenReturn(List.of(list));
        ReadingListBookActionUiService service = new ReadingListBookActionUiService(lists, add, dialogs, state, selection);
        BookViewModel book = new BookViewModel();
        book.setId(B1.asString());
        book.setTitle("One");

        Menu menu = UiActionTestSupport.fx(() -> service.createMenu(book));
        Method populate = ReadingListBookActionUiService.class.getDeclaredMethod("populate", Menu.class, BookViewModel.class);
        populate.setAccessible(true);
        UiActionTestSupport.fx(() -> { populate.invoke(service, menu, book); return null; });
        MenuItem item = menu.getItems().getFirst();
        UiActionTestSupport.fx(() -> { item.fire(); return null; });

        verify(add).execute("list-1", List.of(B1, B2), "c1");
        assertThat(state.getStatusBar().statusTextProperty().get()).contains("Queue");
    }
}
