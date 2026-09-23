package com.myhomelibcorp.ui.action;

import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** JavaFX adapter for profile-driven book actions. */
@Component
@RequiredArgsConstructor
@Slf4j
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
        Menu actions = new Menu(UiText.text("ui.java.book_action_ui_service.ce75e3d4fd"));
        for (BookActionProfile profile : profiles) {
            MenuItem item = new MenuItem(profile.name());
            item.setOnAction(e -> run(book, profile));
            actions.getItems().add(item);
        }
        context.getItems().add(actions);
        return context;
    }

    private void run(BookViewModel book, BookActionProfile profile) {
        appState.getStatusBar().setStatusText(UiText.text("ui.java.book_action_ui_service.93e438617f") + profile.name());
        runBookActionUseCase.execute(BookId.fromString(book.getId()), profile.id())
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        appState.getStatusBar().setStatusText(UiText.text("ui.java.book_action_ui_service.53fff1acbb"));
                        dialogs.showError(UiText.text("ui.java.book_action_ui_service.91d8d4f2be"), UiExceptionSupport.message(error));
                    } else if (result == null || !result.success()) {
                        appState.getStatusBar().setStatusText(UiText.text("ui.java.book_action_ui_service.42c9ca3858"));
                        if (result != null && !result.errors().isEmpty()) {
                            log.error("Book action {} failed: {}", profile.id(), String.join(" | ", result.errors()));
                        }
                        String message = result == null
                                ? UiText.text("ui.java.book_action_ui_service.ff60a5d911")
                                : UiText.text("common.error.operation_failed") + "\n" + UiText.text("common.error.details_in_log");
                        dialogs.showError(UiText.text("ui.java.book_action_ui_service.91d8d4f2be"), message);
                    } else {
                        appState.getStatusBar().setStatusText(UiText.text("ui.java.book_action_ui_service.9086237da0") + profile.name());
                    }
                }));
    }
}
