package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.TocEntry;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class TOCDialogController {

    @FXML
    private ListView<TocEntry> tocListView;

    private Consumer<TocEntry> onEntrySelected;

    @FXML
    public void initialize() {
        tocListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(TocEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String indent = "  ".repeat(Math.max(0, Math.min(8, item.level())));
                    setText(indent + item.title());
                }
            }
        });

        tocListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TocEntry selected = tocListView.getSelectionModel().getSelectedItem();
                if (selected != null && onEntrySelected != null) {
                    onEntrySelected.accept(selected);
                    closeDialog();
                }
            }
        });
    }

    /**
     * Shows the full parser TOC, including nested sections. Entries are flattened
     * for the compact dialog but keep their level as visual indentation.
     */
    public void setEntries(List<TocEntry> entries, Consumer<TocEntry> onEntrySelected) {
        this.onEntrySelected = onEntrySelected;
        List<TocEntry> flat = new ArrayList<>();
        flatten(entries, flat, 0);
        tocListView.getItems().setAll(flat);
    }

    private void flatten(List<TocEntry> entries, List<TocEntry> target, int inheritedLevel) {
        if (entries == null) return;
        for (TocEntry entry : entries) {
            if (entry == null) continue;
            int level = Math.max(inheritedLevel, entry.level());
            TocEntry display = new TocEntry(entry.title(), entry.textOffset(), level, List.of());
            target.add(display);
            if (entry.children() != null && !entry.children().isEmpty()) {
                flatten(entry.children(), target, level + 1);
            }
        }
    }

    @FXML
    private void onClose() {
        closeDialog();
    }

    private void closeDialog() {
        Stage stage = (Stage) tocListView.getScene().getWindow();
        if (stage != null) stage.close();
    }
}
