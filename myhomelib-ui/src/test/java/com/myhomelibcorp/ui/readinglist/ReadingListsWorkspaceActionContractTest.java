package com.myhomelibcorp.ui.readinglist;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.readinglist.*;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadingListsWorkspaceActionContractTest {
    private static final BookId B1 = BookId.fromString("00000000-0000-0000-0000-000000000001");
    private static final BookId B2 = BookId.fromString("00000000-0000-0000-0000-000000000002");
    private static final BookId B3 = BookId.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeAll
    static void startFx() throws Exception {
        UiActionTestSupport.startFx();
    }

    @Test
    void ReadingListsWorkspaceController_onCreate_onRenameAndOnDeleteDelegateVisibleConfirmedMutations() throws Exception {
        Fixture f = fixture();
        when(f.dialogs.showTextInput(anyString(), anyString(), anyString(), eq(""))).thenReturn(Optional.of("New Queue"));
        when(f.dialogs.showTextInput(anyString(), anyString(), anyString(), eq("Queue"))).thenReturn(Optional.of("Renamed"));
        when(f.dialogs.showConfirmation(anyString(), anyString(), anyString())).thenReturn(true);

        invoke(f.controller, "onCreate");
        invoke(f.controller, "onRename");
        invoke(f.controller, "onDelete");

        verify(f.create).execute("New Queue", "", "c1");
        verify(f.rename).execute("list-1", "Renamed", "c1");
        verify(f.delete).execute("list-1", "c1");
        verify(f.dialogs).showConfirmation(anyString(), contains("Queue"), anyString());
    }

    @Test
    void ReadingListsWorkspaceController_onAddSelectedBookUsesWholeCheckboxSelectionAndOnRemoveUsesWholeListSelection() throws Exception {
        Fixture f = fixture();
        f.selection.setSelectedIds(List.of(B1, B3), true);

        invoke(f.controller, "onAddSelectedBook");
        UiActionTestSupport.fx(() -> {
            f.items.getSelectionModel().clearSelection();
            f.items.getSelectionModel().selectIndices(1, 2);
            return null;
        });
        assertThat(f.items.getItems()).hasSize(3);
        assertThat(f.items.getSelectionModel().getSelectedIndices()).containsExactly(1, 2);
        invoke(f.controller, "onRemove");

        verify(f.add).execute("list-1", List.of(B1, B3), "c1");
        verify(f.remove).execute("list-1", List.of(B2, B3), "c1");
    }

    @Test
    void ReadingListsWorkspaceController_onMoveUp_onMoveDownAndAltKeyboardPersistDeterministicOrder() throws Exception {
        Fixture f = fixture();
        UiActionTestSupport.fx(() -> { f.items.getSelectionModel().select(1); return null; });

        invoke(f.controller, "onMoveUp");
        verify(f.reorder).execute("list-1", List.of(B2, B1, B3), "c1");

        reset(f.reorder);
        UiActionTestSupport.fx(() -> { f.items.getSelectionModel().select(1); return null; });
        invoke(f.controller, "onMoveDown");
        verify(f.reorder).execute("list-1", List.of(B1, B3, B2), "c1");

        reset(f.reorder);
        UiActionTestSupport.fx(() -> {
            f.items.getSelectionModel().select(1);
            f.items.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.UP, false, false, true, false));
            return null;
        });
        verify(f.reorder).execute("list-1", List.of(B2, B1, B3), "c1");
    }

    @Test
    void ReadingListsWorkspaceController_onResumeNavigatesToFirstUnfinishedBook() throws Exception {
        Fixture f = fixture();
        BookDto book = mock(BookDto.class);
        when(book.getId()).thenReturn(B3.asString());
        when(f.resume.execute("list-1")).thenReturn(Optional.of(book));

        invoke(f.controller, "onResume");

        verify(f.navigation).navigateToBook(B3);
    }

    @Test
    void ReadingListsWorkspaceController_dragAndDropReorderForDropMovesToEndWithObservableOrder() {
        List<ReadingListItemDto> source = items();

        List<ReadingListItemDto> reordered = ReadingListsWorkspaceController.reorderForDrop(source, 0, source.size());

        assertThat(reordered).extracting(ReadingListItemDto::bookId)
                .containsExactly(B2.asString(), B3.asString(), B1.asString());
        assertThat(source).extracting(ReadingListItemDto::bookId)
                .containsExactly(B1.asString(), B2.asString(), B3.asString());
    }

    private static Fixture fixture() throws Exception {
        ListReadingListsUseCase list = mock(ListReadingListsUseCase.class);
        LoadReadingListUseCase load = mock(LoadReadingListUseCase.class);
        CreateReadingListUseCase create = mock(CreateReadingListUseCase.class);
        RenameReadingListUseCase rename = mock(RenameReadingListUseCase.class);
        DeleteReadingListUseCase delete = mock(DeleteReadingListUseCase.class);
        AddBooksToReadingListUseCase add = mock(AddBooksToReadingListUseCase.class);
        RemoveReadingListItemsUseCase remove = mock(RemoveReadingListItemsUseCase.class);
        ReorderReadingListUseCase reorder = mock(ReorderReadingListUseCase.class);
        FindReadingListResumeItemUseCase resume = mock(FindReadingListResumeItemUseCase.class);
        DialogService dialogs = mock(DialogService.class);
        NavigationService navigation = mock(NavigationService.class);
        ApplicationState state = new ApplicationState();
        BookSelectionService selection = new BookSelectionService(state);
        state.setCurrentLibraryCollection(new Collection("c1", "Test", Path.of("."), null, 1, null, null, null, null));

        ReadingListDto dto = new ReadingListDto("list-1", "Queue", "", 3, LocalDateTime.now(), LocalDateTime.now());
        when(list.execute()).thenReturn(List.of(dto));
        when(load.execute("list-1")).thenReturn(new ReadingListDetailsDto(dto, items()));
        when(resume.execute(anyString())).thenReturn(Optional.empty());

        ReadingListsWorkspaceController controller = new ReadingListsWorkspaceController(
                list, load, create, rename, delete, add, remove, reorder, resume, state, selection, dialogs, navigation);
        ListView<ReadingListDto> listsView = UiActionTestSupport.fx(ListView::new);
        TableView<ReadingListItemDto> itemsTable = UiActionTestSupport.fx(TableView::new);
        setField(controller, "listsView", listsView);
        setField(controller, "listTitle", UiActionTestSupport.fx(Label::new));
        setField(controller, "listSummary", UiActionTestSupport.fx(Label::new));
        setField(controller, "detailsBox", UiActionTestSupport.fx(VBox::new));
        setField(controller, "itemsTable", itemsTable);
        setField(controller, "positionColumn", UiActionTestSupport.fx(TableColumn::new));
        setField(controller, "titleColumn", UiActionTestSupport.fx(TableColumn::new));
        setField(controller, "authorColumn", UiActionTestSupport.fx(TableColumn::new));
        setField(controller, "seriesColumn", UiActionTestSupport.fx(TableColumn::new));
        setField(controller, "progressColumn", UiActionTestSupport.fx(TableColumn::new));
        UiActionTestSupport.fx(() -> { controller.initialize(); return null; });
        return new Fixture(controller, create, rename, delete, add, remove, reorder, resume, dialogs, navigation, selection, itemsTable);
    }

    private static List<ReadingListItemDto> items() {
        return List.of(
                new ReadingListItemDto(B1.asString(), 0, "One", "A", "S", 1, true, 100),
                new ReadingListItemDto(B2.asString(), 1, "Two", "A", "S", 2, true, 20),
                new ReadingListItemDto(B3.asString(), 2, "Three", "A", "S", 3, true, 0));
    }

    private static void invoke(ReadingListsWorkspaceController target, String name) throws Exception {
        Method method = ReadingListsWorkspaceController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        UiActionTestSupport.fx(() -> {
            try { method.invoke(target); }
            catch (ReflectiveOperationException e) { throw new RuntimeException(e.getCause() == null ? e : e.getCause()); }
            return null;
        });
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(ReadingListsWorkspaceController controller,
                           CreateReadingListUseCase create, RenameReadingListUseCase rename,
                           DeleteReadingListUseCase delete, AddBooksToReadingListUseCase add,
                           RemoveReadingListItemsUseCase remove, ReorderReadingListUseCase reorder,
                           FindReadingListResumeItemUseCase resume, DialogService dialogs,
                           NavigationService navigation, BookSelectionService selection,
                           TableView<ReadingListItemDto> items) { }
}
