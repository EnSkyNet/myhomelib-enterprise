package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.ui.filter.BookFilterDialogService;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookTableViewModel;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import static com.myhomelibcorp.ui.support.UiActionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookTableActionBehaviorContractTest {
    @BeforeAll static void fxStart() throws Exception { startFx(); }

    @Test
    void openGlobalFiltersInvokesDialogBoundary() throws Exception {
        var f = constructWithMocks(BookTableController.class);
        TableView<?> table = fx(TableView::new);
        setField(f.instance(), "bookTableView", table);
        when(f.dependency(BookFilterDialogService.class).show(any())).thenReturn(Optional.empty());

        f.instance().openGlobalFilters();

        verify(f.dependency(BookFilterDialogService.class)).show(any());
    }

    @Test
    void quickFilterValueFieldActionPersistsFilterAndReloadsLastQuery() throws Exception {
        var f = constructWithMocks(BookTableController.class);
        BookFilterStateService filters = f.dependency(BookFilterStateService.class);
        when(filters.current()).thenReturn(BookFilterSpec.empty());
        BookTableViewModel vm = new BookTableViewModel();
        when(f.dependency(ApplicationState.class).getBookTable()).thenReturn(vm);
        when(f.dependency(LocalizationService.class).text(any())).thenReturn("text");
        ComboBox<com.myhomelibcorp.application.filter.BookQuickFilterField> columns = fx(ComboBox::new);
        TextField value = fx(TextField::new);
        setField(f.instance(), "quickFilterColumnComboBox", columns);
        setField(f.instance(), "quickFilterValueField", value);
        setField(f.instance(), "filterIndicatorLabel", fx(Label::new));
        Method setup = BookTableController.class.getDeclaredMethod("setupQuickFilter");
        setup.setAccessible(true);
        fx(() -> { setup.invoke(f.instance()); value.setText("needle"); value.fireEvent(new javafx.event.ActionEvent()); return null; });

        verify(filters).save(argThat(spec -> "needle".equals(spec.quickValue())));
        verify(f.dependency(NavigationPanelController.class)).refreshForFilterChange();
        verify(f.dependency(BookLoaderService.class)).reloadLastQuery();
    }


    @Test
    void resetTableProfileRestoresDefaultSortAndClearsPersistedProfile() throws Exception {
        var f = constructWithMocks(BookTableController.class);
        TableView<BookViewModel> table = fx(TableView::new);
        setField(f.instance(), "bookTableView", table);
        for (String name : new String[]{"titleColumn","authorColumn","seriesColumn","genresColumn","fileSizeColumn","rateColumn","progressColumn","activityColumn","dateColumn"}) {
            setField(f.instance(), name, fx(TableColumn::new));
        }

        f.instance().resetTableProfile();

        verify(f.dependency(TableProfileService.class)).reset("default");
        verify(f.dependency(BookLoaderService.class)).setSort(
                com.myhomelibcorp.application.query.common.SortBy.TITLE,
                com.myhomelibcorp.application.query.common.SortDirection.ASC);
        assertThat(fx(table::getSortOrder)).isEmpty();
    }

    @Test
    void checkBoxActionUpdatesBoundBookSelection() throws Exception {
        var f = constructWithMocks(BookTableController.class);
        Method createSelectionCell = BookTableController.class.getDeclaredMethod("createSelectionCell");
        createSelectionCell.setAccessible(true);
        @SuppressWarnings("unchecked") TableCell<BookViewModel, Boolean> cell =
                fx(() -> (TableCell<BookViewModel, Boolean>) createSelectionCell.invoke(f.instance()));
        BookViewModel book = new BookViewModel(); book.setId("1"); book.setTitle("Book");
        java.lang.reflect.Field boundRow = cell.getClass().getDeclaredField("boundRow"); boundRow.setAccessible(true); boundRow.set(cell, book);
        java.lang.reflect.Field checkBoxField = cell.getClass().getDeclaredField("checkBox"); checkBoxField.setAccessible(true);
        CheckBox checkBox = (CheckBox) checkBoxField.get(cell);

        fx(() -> { checkBox.setSelected(true); checkBox.fireEvent(new javafx.event.ActionEvent()); return null; });

        assertThat(book.isSelected()).isTrue();
    }

    @Test
    void showColumnChooserOpensCloseableColumnsDialog() throws Exception {
        var f = constructWithMocks(BookTableController.class);
        LocalizationService i18n = f.dependency(LocalizationService.class);
        when(i18n.text(any())).thenAnswer(inv -> inv.getArgument(0));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> title = new AtomicReference<>();
        Method method = BookTableController.class.getDeclaredMethod("showColumnChooser");
        method.setAccessible(true);

        Platform.runLater(() -> {
            try { method.invoke(f.instance()); }
            catch (Throwable t) { error.set(t.getCause() == null ? t : t.getCause()); }
            finally { done.countDown(); }
        });
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                title.set(pane.getScene().getWindow() instanceof javafx.stage.Stage st ? st.getTitle() : "");
                ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
            } catch (Throwable t) { error.set(t); }
        });

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
        assertThat(title.get()).contains("ui.text.table_columns");
    }

    private static DialogPane latestDialogPane() {
        return Window.getWindows().stream().filter(Window::isShowing)
                .map(Window::getScene).filter(java.util.Objects::nonNull).map(javafx.scene.Scene::getRoot)
                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).reduce((a,b)->b).orElseThrow();
    }

    @Test
    void pageSizeComboBoxActionDelegatesSelectedSizeToLoader() throws Exception {
        var f = constructWithMocks(BookTableController.class);
        ApplicationState state = f.dependency(ApplicationState.class);
        BookTableViewModel vm = new BookTableViewModel();
        when(state.getBookTable()).thenReturn(vm);
        when(f.dependency(LocalizationService.class).text(any())).thenAnswer(i -> "ui.text.page_of".equals(i.getArgument(0)) ? "%d / %d" : "text");
        ComboBox<Integer> pageSize = fx(ComboBox::new);
        setField(f.instance(), "pageSizeComboBox", pageSize);
        setField(f.instance(), "prevPageButton", fx(Button::new));
        setField(f.instance(), "nextPageButton", fx(Button::new));
        setField(f.instance(), "pageInfoLabel", fx(Label::new));
        setField(f.instance(), "filterIndicatorLabel", fx(Label::new));
        Method setup = BookTableController.class.getDeclaredMethod("setupPagination");
        setup.setAccessible(true);
        fx(() -> { setup.invoke(f.instance()); pageSize.setValue(100); pageSize.fireEvent(new javafx.event.ActionEvent()); return null; });

        verify(f.dependency(BookLoaderService.class)).setPageSize(100);
        assertThat(fx(pageSize::getValue)).isEqualTo(100);
    }
}
