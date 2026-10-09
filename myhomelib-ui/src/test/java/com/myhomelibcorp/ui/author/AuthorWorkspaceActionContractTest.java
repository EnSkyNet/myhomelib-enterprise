package com.myhomelibcorp.ui.author;

import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.application.settings.UiPreferenceService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.myhomelibcorp.ui.support.UiActionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthorWorkspaceActionContractTest {
    @BeforeAll static void fxStart() throws Exception { startFx(); }

    @Test
    void sortHandlersPersistExactSortContractsAndCollapseExpandMutateSeriesState() throws Exception {
        var fixture = constructWithMocks(AuthorWorkspaceController.class);
        AuthorWorkspaceController controller = fixture.instance();
        UiPreferenceService preferences = fixture.dependency(UiPreferenceService.class);

        invoke(controller, "onSortBySeries");
        assertThat(getField(controller, "currentSort")).isEqualTo(SortBy.SERIES);
        assertThat(getField(controller, "currentDirection")).isEqualTo(SortDirection.ASC);
        verify(preferences).put("author.workspace.sort", SortBy.SERIES.name());

        invoke(controller, "onSortByTitle");
        assertThat(getField(controller, "currentSort")).isEqualTo(SortBy.TITLE);
        assertThat(getField(controller, "currentDirection")).isEqualTo(SortDirection.ASC);
        invoke(controller, "onSortByYear");
        assertThat(getField(controller, "currentSort")).isEqualTo(SortBy.YEAR);
        assertThat(getField(controller, "currentDirection")).isEqualTo(SortDirection.DESC);
        invoke(controller, "onSortByRating");
        assertThat(getField(controller, "currentSort")).isEqualTo(SortBy.RATING);
        assertThat(getField(controller, "currentDirection")).isEqualTo(SortDirection.DESC);

        BookViewModel a = new BookViewModel(); a.setSeries("Series A");
        BookViewModel b = new BookViewModel(); b.setSeries("Series B");
        @SuppressWarnings("unchecked") List<BookViewModel> all = (List<BookViewModel>) getField(controller, "allBooks");
        all.addAll(List.of(a, b));
        setField(controller, "currentSort", SortBy.TITLE);
        invoke(controller, "onCollapseAll");
        @SuppressWarnings("unchecked") Set<String> collapsed = (Set<String>) getField(controller, "collapsedSeries");
        assertThat(collapsed).containsExactlyInAnyOrder("series a", "series b");
        assertThat(getField(controller, "currentSort")).isEqualTo(SortBy.SERIES);
        invoke(controller, "onExpandAll");
        assertThat(collapsed).isEmpty();
    }

    @Test
    void masterSelectionCheckBoxSelectsAllConcreteAuthorBooks() throws Exception {
        var fixture = constructWithMocks(AuthorWorkspaceController.class);
        AuthorWorkspaceController controller = fixture.instance();
        LocalizationService i18n = fixture.dependency(LocalizationService.class);
        BookSelectionService selection = fixture.dependency(BookSelectionService.class);
        when(i18n.text(any())).thenAnswer(i -> i.getArgument(0));
        when(i18n.format(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(selection.state(anyList())).thenReturn(BookSelectionService.SelectionState.NONE);
        TableView<BookViewModel> table = fx(TableView::new);
        setField(controller, "booksTableView", table);
        @SuppressWarnings("unchecked") List<BookViewModel> all = (List<BookViewModel>) getField(controller, "allBooks");
        BookViewModel first = new BookViewModel(); first.setId("1"); first.setTitle("A");
        BookViewModel second = new BookViewModel(); second.setId("2"); second.setTitle("B");
        all.addAll(List.of(first, second));

        java.lang.reflect.Method configureSelectionColumn = AuthorWorkspaceController.class.getDeclaredMethod("configureSelectionColumn");
        configureSelectionColumn.setAccessible(true);
        fx(() -> { configureSelectionColumn.invoke(controller); return null; });
        javafx.scene.control.CheckBox masterSelectionCheckBox =
                (javafx.scene.control.CheckBox) getField(controller, "masterSelectionCheckBox");
        fx(() -> { masterSelectionCheckBox.fire(); return null; });

        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<java.util.Collection<BookViewModel>> selected =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(selection).setSelected(selected.capture(), eq(true));
        assertThat(selected.getValue()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void checkBoxActionUpdatesBoundConcreteBookSelection() throws Exception {
        var fixture = constructWithMocks(AuthorWorkspaceController.class);
        AuthorWorkspaceController controller = fixture.instance();
        setField(controller, "booksTableView", fx(TableView::new));
        BookViewModel book = new BookViewModel(); book.setId("1"); book.setTitle("A");
        java.lang.reflect.Method createSelectionCell = AuthorWorkspaceController.class.getDeclaredMethod("createSelectionCell");
        createSelectionCell.setAccessible(true);
        javafx.scene.control.TableCell<BookViewModel, Boolean> cell =
                fx(() -> (javafx.scene.control.TableCell<BookViewModel, Boolean>) createSelectionCell.invoke(controller));
        java.lang.reflect.Field boundRow = cell.getClass().getDeclaredField("boundRow"); boundRow.setAccessible(true); boundRow.set(cell, book);
        java.lang.reflect.Field checkBoxField = cell.getClass().getDeclaredField("checkBox"); checkBoxField.setAccessible(true);
        javafx.scene.control.CheckBox checkBox = (javafx.scene.control.CheckBox) checkBoxField.get(cell);
        fx(() -> { checkBox.setSelected(true); checkBox.fireEvent(new javafx.event.ActionEvent()); return null; });

        assertThat(book.isSelected()).isTrue();
    }

    @Test
    void localFilterActionRebuildsVisibleRowsFromSelectedMode() throws Exception {
        var fixture = constructWithMocks(AuthorWorkspaceController.class);
        AuthorWorkspaceController controller = fixture.instance();
        LocalizationService i18n = fixture.dependency(LocalizationService.class);
        when(i18n.text(any())).thenAnswer(i -> i.getArgument(0));
        when(i18n.format(any(), any())).thenAnswer(i -> i.getArgument(0));

        ComboBox<String> filter = fx(ComboBox::new);
        TextField text = fx(TextField::new);
        TableView<BookViewModel> table = fx(TableView::new);
        setField(controller, "localFilterComboBox", filter);
        setField(controller, "filterTextField", text);
        setField(controller, "booksTableView", table);
        setField(controller, "booksCountLabel", fx(Label::new));
        setField(controller, "seriesCountLabel", fx(Label::new));
        setField(controller, "genresCountLabel", fx(Label::new));

        BookViewModel local = new BookViewModel(); local.setId("1"); local.setTitle("Local"); local.setLocal(true);
        BookViewModel remote = new BookViewModel(); remote.setId("2"); remote.setTitle("Remote"); remote.setLocal(false);
        @SuppressWarnings("unchecked") List<BookViewModel> all = (List<BookViewModel>) getField(controller, "allBooks");
        all.addAll(List.of(local, remote));

        java.lang.reflect.Method configure = AuthorWorkspaceController.class.getDeclaredMethod("configureFilters");
        configure.setAccessible(true);
        fx(() -> { configure.invoke(controller); return null; });
        fx(() -> { filter.setValue("ui.author.local_filter.downloaded"); filter.fireEvent(new javafx.event.ActionEvent()); return null; });
        assertThat(fx(() -> List.copyOf(table.getItems()))).containsExactly(local);
    }
}
