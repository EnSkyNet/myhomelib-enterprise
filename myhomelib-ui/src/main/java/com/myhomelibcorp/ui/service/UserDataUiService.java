package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.application.service.PortableUserDataService;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;

/** UI facade for the single schema-versioned portable user-data format. */
@Service
@RequiredArgsConstructor
public class UserDataUiService {
    private final PortableUserDataService transfer;
    private final DialogService dialogs;
    private final LibraryOperationCoordinator operationCoordinator;

    public void exportData(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(UiText.text("ui.java.user_data_ui_service.e8ac5883ab"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(UiText.text("ui.java.user_data_ui_service.02881246d2"), "*.mhluserdata.json", "*.json"));
        chooser.setInitialFileName("myhomelib-user-data.mhluserdata.json");
        File file = chooser.showSaveDialog(owner);
        if (file == null) return;

        LibraryOperationCoordinator.Lease lease = null;
        try {
            lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EXPORT);
            var result = transfer.exportTo(file.toPath());
            lease.markCompleted("");
            dialogs.showInfo(UiText.text("ui.java.user_data_ui_service.61676a8b43"), String.format(
                    UiText.text("ui.java.user_data_ui_service.7fa0c91dbc") +
                            UiText.text("ui.java.user_data_ui_service.2a218a0017"),
                    result.schemaVersion(), result.bookRecords(), result.groupMemberships(), result.bookmarks(),
                    result.annotations(), result.historyEntries(), result.savedSearches(), result.readerOverrides(), file));
        } catch (Exception e) {
            if (lease != null) lease.markFailed(e);
            dialogs.showError(UiText.text("ui.java.user_data_ui_service.68c7ce9fff"), UiExceptionSupport.message(e));
        } finally {
            if (lease != null) lease.close();
        }
    }

    public void importData(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(UiText.text("ui.java.user_data_ui_service.560436e3f5"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(UiText.text("ui.java.user_data_ui_service.02881246d2"), "*.mhluserdata.json", "*.json"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) return;

        LibraryOperationCoordinator.Lease lease = null;
        try {
            lease = operationCoordinator.acquireDetached(LibraryOperationType.RESTORE);
            var result = transfer.restoreFrom(file.toPath());
            lease.markCompleted("");
            dialogs.showInfo(UiText.text("ui.java.user_data_ui_service.309ba8f16f"), String.format(
                    UiText.text("ui.java.user_data_ui_service.92fe4cf56f") +
                            UiText.text("ui.java.user_data_ui_service.b22c3481cc") +
                            UiText.text("ui.java.user_data_ui_service.607ab54eca"),
                    result.sourceSchemaVersion(), result.effectiveSchemaVersion(), result.matchedBooks(),
                    result.unmatchedBooks(), result.groups(), result.groupMemberships(), result.bookmarks(),
                    result.annotations(), result.historyEntries(), result.savedSearches(), result.readerOverrides()));
        } catch (Exception e) {
            if (lease != null) lease.markFailed(e);
            dialogs.showError(UiText.text("ui.java.user_data_ui_service.0d5508af6e"), UiExceptionSupport.message(e));
        } finally {
            if (lease != null) lease.close();
        }
    }
}
