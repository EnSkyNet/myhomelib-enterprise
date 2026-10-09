package com.myhomelibcorp.ui.annotation;

import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import javafx.scene.control.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static com.myhomelibcorp.ui.support.UiActionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnnotationManagerActionBehaviorContractTest {
    @BeforeAll static void fxStart() throws Exception { startFx(); }

    @Test
    void refreshAndApplyFiltersSubmitFreshQueriesAndResetOffset() throws Exception {
        var f = fixture();
        UiBackgroundExecutor executor = f.dependency(UiBackgroundExecutor.class);
        when(executor.submitCollectionScoped(eq(LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class)))
                .thenReturn(new CompletableFuture<>());
        setField(f.instance(), "offset", 200);

        f.instance().refresh();
        f.instance().applyFilters();

        assertThat(getField(f.instance(), "offset")).isEqualTo(0);
        verify(executor, times(2)).submitCollectionScoped(eq(LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class));
    }

    @Test
    void clearFiltersClearsVisibleCriteriaAndResetsPagingBeforeQuery() throws Exception {
        var f = fixture();
        UiBackgroundExecutor executor = f.dependency(UiBackgroundExecutor.class);
        when(executor.submitCollectionScoped(eq(LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class)))
                .thenReturn(new CompletableFuture<>());
        TextField search = (TextField) getField(f.instance(), "searchField");
        DatePicker from = (DatePicker) getField(f.instance(), "dateFromFilter");
        DatePicker to = (DatePicker) getField(f.instance(), "dateToFilter");
        fx(() -> { search.setText("needle"); from.setValue(java.time.LocalDate.now()); to.setValue(java.time.LocalDate.now()); return null; });
        setField(f.instance(), "offset", 100);

        f.instance().clearFilters();

        assertThat((String) fx(search::getText)).isEmpty();
        assertThat(fx(from::getValue)).isNull();
        assertThat(fx(to::getValue)).isNull();
        assertThat(getField(f.instance(), "offset")).isEqualTo(0);
        verify(executor).submitCollectionScoped(eq(LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class));
    }

    private static Fixture<AnnotationManagerWorkspaceController> fixture() throws Exception {
        var f = constructWithMocks(AnnotationManagerWorkspaceController.class);
        LocalizationService i18n = f.dependency(LocalizationService.class);
        when(i18n.text(anyString())).thenAnswer(i -> i.getArgument(0));
        when(i18n.format(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        setField(f.instance(), "searchField", fx(TextField::new));
        setField(f.instance(), "bookFilter", fx(ComboBox::new));
        setField(f.instance(), "typeFilter", fx(ComboBox::new));
        setField(f.instance(), "colorFilter", fx(ComboBox::new));
        setField(f.instance(), "tagFilter", fx(ComboBox::new));
        setField(f.instance(), "dateFromFilter", fx(DatePicker::new));
        setField(f.instance(), "dateToFilter", fx(DatePicker::new));
        setField(f.instance(), "table", fx(TableView::new));
        setField(f.instance(), "statusLabel", fx(Label::new));
        setField(f.instance(), "selectedLabel", fx(Label::new));
        setField(f.instance(), "pageLabel", fx(Label::new));
        setField(f.instance(), "progressIndicator", fx(ProgressIndicator::new));
        for (String name : new String[]{"openButton","editButton","deleteButton","undoButton","batchColorButton","batchTagsButton","batchRemoveTagsButton","exportSelectedButton","previousButton","nextButton"})
            setField(f.instance(), name, fx(Button::new));
        return f;
    }
}
