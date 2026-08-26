package com.myhomelibcorp.ui.action;

import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.usecase.book.RunBookActionUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** JavaFX adapter for profile-driven book actions. */
@Component
@RequiredArgsConstructor
public class BookActionUiService {
    private final BookActionProfileService profileService;
    private final RunBookActionUseCase runBookActionUseCase;
    private final DialogService dialogs;
    private final ApplicationState appState;

    public ContextMenu createContextMenu(BookViewModel book) {
        if (book == null || book.getId() == null || book.isGroupHeader()) return null;
        List<BookActionProfile> profiles = profileService.loadProfiles().stream()
                .filter(BookActionProfile::enabled)
                .filter(profile -> !profile.commands().isEmpty())
                .toList();
        if (profiles.isEmpty()) return null;
        ContextMenu context = new ContextMenu();
        Menu actions = new Menu("Дії з книгою");
        for (BookActionProfile profile : profiles) {
            MenuItem item = new MenuItem(profile.name());
            item.setOnAction(e -> run(book, profile));
            actions.getItems().add(item);
        }
        context.getItems().add(actions);
        return context;
    }

    private void run(BookViewModel book, BookActionProfile profile) {
        appState.getStatusBar().setStatusText("Запуск дії: " + profile.name());
        runBookActionUseCase.execute(BookId.fromString(book.getId()), profile.id())
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        appState.getStatusBar().setStatusText("Помилка дії з книгою");
                        dialogs.showError("Дія з книгою", error.getMessage());
                    } else if (result == null || !result.success()) {
                        appState.getStatusBar().setStatusText("Дія завершилась з помилкою");
                        String message = result == null ? "Невідома помилка" : String.join("\n", result.errors());
                        dialogs.showError("Дія з книгою", message);
                    } else {
                        appState.getStatusBar().setStatusText("Дію запущено: " + profile.name());
                    }
                }));
    }
}
