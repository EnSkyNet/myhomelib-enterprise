package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.service.PortableUserDataService;
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

    public void exportData(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Експорт користувацьких даних");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("MyHomeLib user data", "*.mhluserdata.json", "*.json"));
        chooser.setInitialFileName("myhomelib-user-data.mhluserdata.json");
        File file = chooser.showSaveDialog(owner);
        if (file == null) return;

        try {
            var result = transfer.exportTo(file.toPath());
            dialogs.showInfo("Експорт завершено", String.format(
                    "Схема: v%d%nКнижкові записи: %d%nГрупові зв’язки: %d%nЗакладки: %d%n" +
                            "Історія: %d%nЗбережені пошуки: %d%nReader overrides: %d%n%n%s",
                    result.schemaVersion(), result.bookRecords(), result.groupMemberships(), result.bookmarks(),
                    result.historyEntries(), result.savedSearches(), result.readerOverrides(), file));
        } catch (Exception e) {
            dialogs.showError("Помилка експорту", e.getMessage());
        }
    }

    public void importData(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Імпорт користувацьких даних");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("MyHomeLib user data", "*.mhluserdata.json", "*.json"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) return;

        try {
            var result = transfer.restoreFrom(file.toPath());
            dialogs.showInfo("Імпорт завершено", String.format(
                    "Схема: v%d → v%d%nЗіставлено книг: %d%nНе зіставлено: %d%n" +
                            "Групи: %d%nГрупові зв’язки: %d%nЗакладки: %d%nІсторія: %d%n" +
                            "Збережені пошуки: %d%nReader overrides: %d",
                    result.sourceSchemaVersion(), result.effectiveSchemaVersion(), result.matchedBooks(),
                    result.unmatchedBooks(), result.groups(), result.groupMemberships(), result.bookmarks(),
                    result.historyEntries(), result.savedSearches(), result.readerOverrides()));
        } catch (Exception e) {
            dialogs.showError("Помилка імпорту", e.getMessage());
        }
    }
}
