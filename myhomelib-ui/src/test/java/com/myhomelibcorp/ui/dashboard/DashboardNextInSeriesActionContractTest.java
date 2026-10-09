package com.myhomelibcorp.ui.dashboard;

import com.myhomelibcorp.application.dto.NextInSeriesItemDto;
import com.myhomelibcorp.application.usecase.dashboard.LoadDashboardDataUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DashboardNextInSeriesActionContractTest {
    @BeforeAll
    static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void DashboardController_openAndSeriesActionsNavigateToExactOnDeckTargets() throws Exception {
        LoadDashboardDataUseCase load = mock(LoadDashboardDataUseCase.class);
        NavigationService navigation = mock(NavigationService.class);
        DashboardController controller = new DashboardController(load, new ApplicationState(), navigation);
        NextInSeriesItemDto item = new NextInSeriesItemDto(
                "00000000-0000-0000-0000-000000000002", "Second", "Saga", 2);
        Method factory = DashboardController.class.getDeclaredMethod("createNextInSeriesRow", NextInSeriesItemDto.class);
        factory.setAccessible(true);

        VBox row = UiActionTestSupport.fx(() -> (VBox) factory.invoke(controller, item));
        HBox header = (HBox) row.getChildren().getFirst();
        Button open = (Button) header.getChildren().get(1);
        Button series = (Button) header.getChildren().get(2);
        UiActionTestSupport.fx(() -> { open.fire(); series.fire(); return null; });

        verify(navigation).navigateToBook(BookId.fromString(item.bookId()));
        verify(navigation).navigateToSeriesByName("Saga");
        assertThat(header.getChildren()).contains(open, series);
    }
}
