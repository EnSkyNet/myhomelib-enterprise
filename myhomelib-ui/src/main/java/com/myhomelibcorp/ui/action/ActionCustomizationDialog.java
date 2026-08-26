package com.myhomelibcorp.ui.action;

import com.myhomelibcorp.application.action.ActionPreference;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage 14 customization dialog for shortcuts and command visibility. */
@Component
@RequiredArgsConstructor
public class ActionCustomizationDialog {
    private final ActionRegistry registry;
    private final LocalizationService i18n;
    private final DialogService dialogs;

    public void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Команди та гарячі клавіші");
        dialog.setHeaderText("Змініть shortcut або приховайте команду. Порожнє поле вимикає shortcut.");
        if (owner != null) dialog.initOwner(owner);
        ButtonType reset = new ButtonType("За замовчуванням", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(reset, ButtonType.CANCEL, ButtonType.OK);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(8));
        grid.addRow(0, new Label("Команда"), new Label("Shortcut"), new Label("Видима"));
        Map<String, Draft> drafts = new LinkedHashMap<>();
        int row = 1;
        for (ActionRegistry.ActionSnapshot action : registry.snapshot()) {
            TextField shortcut = new TextField(action.shortcut());
            shortcut.setPrefColumnCount(18);
            CheckBox visible = new CheckBox();
            visible.setSelected(action.visible());
            Label title = new Label(i18n.tr(action.title()));
            title.setTooltip(new Tooltip(action.id()));
            grid.addRow(row++, title, shortcut, visible);
            drafts.put(action.id(), new Draft(action, shortcut, visible));
        }
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(620);
        scroll.setPrefViewportHeight(430);
        dialog.getDialogPane().setContent(scroll);

        dialog.getDialogPane().lookupButton(reset).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            drafts.values().forEach(d -> {
                d.shortcut.setText(d.snapshot.defaultShortcut());
                d.visible.setSelected(d.snapshot.defaultVisible());
            });
            event.consume();
        });

        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            Map<String, ActionPreference> preferences = collect(drafts);
            List<String> errors = registry.validate(preferences);
            if (!errors.isEmpty()) {
                dialogs.showWarning("Гарячі клавіші", String.join("\n", errors));
                event.consume();
                return;
            }
            registry.apply(preferences);
        });
        dialog.showAndWait();
    }

    private Map<String, ActionPreference> collect(Map<String, Draft> drafts) {
        Map<String, ActionPreference> result = new LinkedHashMap<>();
        drafts.forEach((id, draft) -> result.put(id,
                new ActionPreference(draft.shortcut.getText(), draft.visible.isSelected())));
        return result;
    }

    private record Draft(ActionRegistry.ActionSnapshot snapshot, TextField shortcut, CheckBox visible) { }
}
