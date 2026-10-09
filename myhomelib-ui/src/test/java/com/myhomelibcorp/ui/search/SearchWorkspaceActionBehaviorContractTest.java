package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.ui.filter.BookFilterDialogService;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchWorkspaceActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void onSearchDelegatesCurrentTextToSearchPipeline() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SearchWorkspaceController.class);
        SearchWorkspaceController c = spy(f.instance());
        TextField search = UiActionTestSupport.fx(() -> new TextField("needle"));
        UiActionTestSupport.setField(c, "searchField", search);
        doNothing().when(c).performSearch(anyString());

        UiActionTestSupport.invoke(c, "onSearch");

        verify(c).performSearch("needle");
    }

    @Test
    void masterSelectionCheckBoxSelectsAllSearchResults() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SearchWorkspaceController.class);
        SearchWorkspaceController c = f.instance();
        when(f.dependency(LocalizationService.class).text(anyString())).thenAnswer(i -> i.getArgument(0));
        BookSelectionService selection = f.dependency(BookSelectionService.class);
        when(selection.stateIds(anyList())).thenReturn(BookSelectionService.SelectionState.NONE);
        when(selection.selectedCountProperty()).thenReturn(new javafx.beans.property.ReadOnlyIntegerWrapper(0).getReadOnlyProperty());
        TableView<BookDto> table = UiActionTestSupport.fx(TableView::new);
        TableColumn<BookDto, Void> selectColumn = UiActionTestSupport.fx(TableColumn::new);
        UiActionTestSupport.setField(c, "booksTableView", table);
        UiActionTestSupport.setField(c, "selectColumn", selectColumn);
        BookDto book = BookDto.builder().id("00000000-0000-0000-0000-000000000001").title("Book").build();
        UiActionTestSupport.fx(() -> { table.getItems().add(book); return null; });
        UiActionTestSupport.invoke(c, "configureSelectionColumn");
        CheckBox masterSelectionCheckBox = (CheckBox) UiActionTestSupport.getField(c, "masterSelectionCheckBox");

        UiActionTestSupport.fx(() -> { masterSelectionCheckBox.fire(); return null; });

        verify(selection).setSelectedIds(argThat(ids -> ids.size() == 1 && ids.iterator().next().equals(BookId.fromString(book.getId()))), eq(true));
    }

    @Test
    void checkBoxActionSelectsBookRepresentedByRenderedSearchRow() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SearchWorkspaceController.class);
        SearchWorkspaceController c = f.instance();
        when(f.dependency(LocalizationService.class).text(anyString())).thenAnswer(i -> i.getArgument(0));
        BookSelectionService selection = f.dependency(BookSelectionService.class);
        when(selection.stateIds(anyList())).thenReturn(BookSelectionService.SelectionState.NONE);
        when(selection.selectedCountProperty()).thenReturn(new javafx.beans.property.ReadOnlyIntegerWrapper(0).getReadOnlyProperty());
        when(selection.isSelected(any(BookId.class))).thenReturn(false);
        TableView<BookDto> table = UiActionTestSupport.fx(TableView::new);
        TableColumn<BookDto, Void> selectColumn = UiActionTestSupport.fx(TableColumn::new);
        UiActionTestSupport.setField(c, "booksTableView", table);
        UiActionTestSupport.setField(c, "selectColumn", selectColumn);
        BookDto book = BookDto.builder().id("00000000-0000-0000-0000-000000000002").title("Book").build();
        UiActionTestSupport.fx(() -> {
            table.getItems().add(book);
            table.getColumns().add(selectColumn);
            return null;
        });
        UiActionTestSupport.invoke(c, "configureSelectionColumn");

        Stage stage = UiActionTestSupport.fx(() -> {
            Stage value = new Stage();
            value.setScene(new Scene(new StackPane(table), 420, 220));
            value.show();
            table.applyCss();
            table.layout();
            return value;
        });
        try {
            CheckBox checkBox = UiActionTestSupport.fx(() -> table.lookupAll(".check-box").stream()
                    .filter(CheckBox.class::isInstance).map(CheckBox.class::cast)
                    .filter(SearchWorkspaceActionBehaviorContractTest::insideTableCell)
                    .findFirst().orElseThrow());
            UiActionTestSupport.fx(() -> { checkBox.setSelected(false); checkBox.fire(); return null; });
            verify(selection).setSelected(BookId.fromString(book.getId()), true);
        } finally {
            UiActionTestSupport.fx(() -> { stage.close(); return null; });
        }
    }

    @Test
    void onGlobalFiltersRefreshesNavigationAndReexecutesVisibleQueryWhenAccepted() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SearchWorkspaceController.class);
        SearchWorkspaceController c = spy(f.instance());
        TextField search = UiActionTestSupport.fx(() -> new TextField("query"));
        UiActionTestSupport.setField(c, "searchField", search);
        when(f.dependency(BookFilterDialogService.class).show(isNull())).thenReturn(Optional.of(BookFilterSpec.empty()));
        when(f.dependency(com.myhomelibcorp.application.filter.BookFilterStateService.class).current()).thenReturn(BookFilterSpec.empty());
        doNothing().when(c).performSearch(anyString());

        UiActionTestSupport.invoke(c, "onGlobalFilters");

        verify(f.dependency(NavigationPanelController.class)).refreshForFilterChange();
        verify(c).performSearch("query");
    }

    @Test
    void onClearAndOnClearAdvancedFieldsClearInputsAndRefreshPinnedScope() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SearchWorkspaceController.class);
        SearchWorkspaceController c = spy(f.instance());
        when(f.dependency(LocalizationService.class).text(anyString())).thenAnswer(i -> i.getArgument(0));
        TextField search = UiActionTestSupport.fx(() -> new TextField("needle"));
        UiActionTestSupport.setField(c, "searchField", search);
        String[] fields = {"titleFilter","authorFilter","seriesFilter","genreFilter","keywordFilter","annotationFilter",
                "fileFilter","languageFilter","ratingFromFilter","ratingToFilter","yearFromFilter","yearToFilter"};
        for (String name : fields) UiActionTestSupport.setField(c, name, UiActionTestSupport.fx(() -> new TextField("x")));
        UiActionTestSupport.setField(c, "activeSmartCollectionId", "saved-1");
        doNothing().when(c).performSearch(anyString());

        UiActionTestSupport.invoke(c, "onClearAdvancedFields");
        verify(c).performSearch("needle");
        for (String name : fields) assertThat(UiActionTestSupport.fx(() -> ((TextField) UiActionTestSupport.getField(c, name)).getText())).isEmpty();

        clearInvocations(c);
        UiActionTestSupport.fx(() -> { search.setText("again"); return null; });
        UiActionTestSupport.invoke(c, "onClear");
        assertThat(UiActionTestSupport.fx(() -> search.getText())).isEmpty();
        verify(c).performSearch("");
    }

    private static boolean insideTableCell(javafx.scene.Node node) {
        javafx.scene.Parent parent = node.getParent();
        while (parent != null) {
            if (parent instanceof TableCell<?, ?>) return true;
            parent = parent.getParent();
        }
        return false;
    }
}
