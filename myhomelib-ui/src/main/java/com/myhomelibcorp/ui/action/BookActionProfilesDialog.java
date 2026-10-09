package com.myhomelibcorp.ui.action;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.action.BookActionCommand;
import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionPreview;
import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage 15 named script/book action profile editor with non-executing command preview. */
@Component
@RequiredArgsConstructor
public class BookActionProfilesDialog {
    private final BookActionProfileService profileService;
    private final BookActionExecutionService executionService;
    private final DialogService dialogs;
    private final FileChooserService fileChooserService;

    public void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.setTitle(UiText.text("ui.java.book_action_profiles_dialog.801a9ad241"));
        dialog.setHeaderText(UiText.text("ui.java.book_action_profiles_dialog.8e05e39e0c"));
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        ObservableList<MutableProfile> profiles = FXCollections.observableArrayList(
                profileService.loadProfiles().stream().map(MutableProfile::new).toList());
        ListView<MutableProfile> profileList = new ListView<>(profiles);
        profileList.setPrefWidth(230);
        profileList.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(MutableProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : (item.enabled ? "✓ " : "  ") + item.name);
            }
        });

        TextField name = new TextField();
        CheckBox enabled = new CheckBox(UiText.text("ui.java.book_action_profiles_dialog.a9a6a186b6"));
        ListView<BookActionCommand> commands = new ListView<>();
        commands.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(BookActionCommand item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item.executable() + (item.arguments().isBlank() ? "" : "  " + item.arguments())
                        + (item.waitForExit() ? UiText.text("ui.java.book_action_profiles_dialog.ec401d2a4f") : ""));
            }
        });

        Button addProfile = new Button("+");
        Button removeProfile = new Button("−");
        HBox profileButtons = new HBox(6, addProfile, removeProfile);
        VBox left = new VBox(6, new Label(UiText.text("ui.java.book_action_profiles_dialog.3a9117f73f")), profileList, profileButtons);
        VBox.setVgrow(profileList, Priority.ALWAYS);

        Button addCommand = new Button(UiText.text("ui.java.book_action_profiles_dialog.71835a2a7b"));
        Button editCommand = new Button(UiText.text("ui.annotations.edit"));
        Button removeCommand = new Button(UiText.text("common.delete"));
        Button upCommand = new Button("↑");
        Button downCommand = new Button("↓");
        Button preview = new Button(UiText.text("ui.java.book_action_profiles_dialog.6859608a62"));
        HBox commandButtons = new HBox(6, addCommand, editCommand, removeCommand, upCommand, downCommand, preview);
        commandButtons.setAlignment(Pos.CENTER_LEFT);

        Label placeholders = new Label(UiText.text("ui.java.book_action_profiles_dialog.483628a806"));
        placeholders.setWrapText(true);
        VBox right = new VBox(8,
                new Label(UiText.text("ui.bulkedit.field.title")), name, enabled,
                new Separator(), new Label(UiText.text("ui.java.book_action_profiles_dialog.70e5012ce4")), commands, commandButtons,
                new Separator(), placeholders);
        VBox.setVgrow(commands, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.28);
        split.setPrefSize(920, 560);
        dialog.getDialogPane().setContent(split);

        final boolean[] loading = {false};
        Runnable updateControls = () -> {
            MutableProfile p = profileList.getSelectionModel().getSelectedItem();
            loading[0] = true;
            try {
                name.setDisable(p == null); enabled.setDisable(p == null); commands.setDisable(p == null);
                addCommand.setDisable(p == null); editCommand.setDisable(p == null || commands.getSelectionModel().getSelectedItem() == null);
                removeCommand.setDisable(p == null || commands.getSelectionModel().getSelectedItem() == null);
                upCommand.setDisable(p == null || commands.getSelectionModel().getSelectedIndex() <= 0);
                downCommand.setDisable(p == null || commands.getSelectionModel().getSelectedIndex() < 0
                        || commands.getSelectionModel().getSelectedIndex() >= commands.getItems().size() - 1);
                preview.setDisable(p == null || p.commands.isEmpty());
                removeProfile.setDisable(p == null);
                if (p == null) {
                    name.clear(); enabled.setSelected(false); commands.setItems(FXCollections.observableArrayList());
                } else {
                    name.setText(p.name); enabled.setSelected(p.enabled); commands.setItems(p.commands);
                }
            } finally { loading[0] = false; }
        };
        profileList.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> updateControls.run());
        commands.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> updateControls.run());
        name.textProperty().addListener((o,a,b) -> {
            if (loading[0]) return;
            MutableProfile p = profileList.getSelectionModel().getSelectedItem();
            if (p != null) { p.name = b == null ? "" : b; profileList.refresh(); }
        });
        enabled.selectedProperty().addListener((o,a,b) -> {
            if (loading[0]) return;
            MutableProfile p = profileList.getSelectionModel().getSelectedItem();
            if (p != null) { p.enabled = b; profileList.refresh(); }
        });

        addProfile.setOnAction(e -> {
            BookActionProfile created = profileService.newProfile(UiText.text("ui.java.book_action_profiles_dialog.42c82a3391"));
            MutableProfile mutable = new MutableProfile(created);
            profiles.add(mutable); profileList.getSelectionModel().select(mutable);
        });
        removeProfile.setOnAction(e -> {
            MutableProfile p = profileList.getSelectionModel().getSelectedItem();
            if (p != null) profiles.remove(p);
        });
        addCommand.setOnAction(e -> editCommand(owner, null).ifPresent(c -> {
            MutableProfile p = profileList.getSelectionModel().getSelectedItem();
            if (p != null) p.commands.add(c);
            updateControls.run();
        }));
        editCommand.setOnAction(e -> {
            int index = commands.getSelectionModel().getSelectedIndex();
            BookActionCommand current = commands.getSelectionModel().getSelectedItem();
            if (index >= 0 && current != null) editCommand(owner, current).ifPresent(c -> {
                commands.getItems().set(index, c); commands.getSelectionModel().select(index); updateControls.run();
            });
        });
        removeCommand.setOnAction(e -> {
            int index = commands.getSelectionModel().getSelectedIndex();
            if (index >= 0) commands.getItems().remove(index);
            updateControls.run();
        });
        upCommand.setOnAction(e -> move(commands, -1, updateControls));
        downCommand.setOnAction(e -> move(commands, 1, updateControls));
        preview.setOnAction(e -> {
            MutableProfile p = profileList.getSelectionModel().getSelectedItem();
            if (p != null) showPreview(p.toRecord());
        });

        if (!profiles.isEmpty()) profileList.getSelectionModel().selectFirst(); else updateControls.run();

        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                List<BookActionProfile> records = profiles.stream().map(MutableProfile::toRecord).toList();
                profileService.replaceAll(records);
            } catch (RuntimeException ex) {
                dialogs.showWarning(UiText.text("ui.java.book_action_profiles_dialog.ce75e3d4fd"), UiExceptionSupport.message(ex));
                event.consume();
            }
        });
        dialog.showAndWait();
    }

    private java.util.Optional<BookActionCommand> editCommand(Window owner, BookActionCommand current) {
        Dialog<BookActionCommand> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.setTitle(current == null ? UiText.text("ui.java.book_action_profiles_dialog.a616bb8079") : UiText.text("ui.java.book_action_profiles_dialog.3d7bdde4e7"));
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        TextField executable = new TextField(current == null ? "" : current.executable());
        TextField arguments = new TextField(current == null ? "" : current.arguments());
        TextField working = new TextField(current == null ? "%DIR%" : current.workingDirectory());
        CheckBox wait = new CheckBox(UiText.text("ui.java.book_action_profiles_dialog.35ab66f76d"));
        wait.setSelected(current != null && current.waitForExit());
        Button chooseExe = new Button("...");
        chooseExe.setOnAction(e -> {
            File file = fileChooserService.chooseFile(owner, UiText.text("ui.java.book_action_profiles_dialog.2a2405437c"), List.of());
            if (file != null) executable.setText(file.getAbsolutePath());
        });
        Button chooseDir = new Button("...");
        chooseDir.setOnAction(e -> {
            File dir = fileChooserService.chooseDirectory(owner, UiText.text("ui.java.book_action_profiles_dialog.c97684da7c"));
            if (dir != null) working.setText(dir.getAbsolutePath());
        });
        GridPane grid = new GridPane(); grid.setHgap(8); grid.setVgap(8); grid.setPadding(new Insets(8));
        grid.add(new Label(UiText.text("ui.java.book_action_profiles_dialog.d91bea5e26")),0,0); grid.add(executable,1,0); grid.add(chooseExe,2,0);
        grid.add(new Label(UiText.text("ui.java.book_action_profiles_dialog.7842d0ae44")),0,1); grid.add(arguments,1,1,2,1);
        grid.add(new Label(UiText.text("ui.java.book_action_profiles_dialog.c97684da7c")),0,2); grid.add(working,1,2); grid.add(chooseDir,2,2);
        grid.add(wait,1,3,2,1);
        ColumnConstraints value = new ColumnConstraints(); value.setHgrow(Priority.ALWAYS); grid.getColumnConstraints().addAll(new ColumnConstraints(), value, new ColumnConstraints());
        dialog.getDialogPane().setContent(grid); dialog.getDialogPane().setPrefWidth(760);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                if (executable.getText() == null || executable.getText().isBlank()) throw new IllegalArgumentException(UiText.text("ui.java.book_action_profiles_dialog.62873130e6"));
                CommandTemplate.parse(arguments.getText());
            } catch (RuntimeException ex) {
                dialogs.showWarning(UiText.text("ui.java.book_action_profiles_dialog.0fe2342c2e"), UiExceptionSupport.message(ex)); event.consume();
            }
        });
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? new BookActionCommand(executable.getText(), arguments.getText(), working.getText(), wait.isSelected()) : null);
        return dialog.showAndWait();
    }

    private void move(ListView<BookActionCommand> commands, int delta, Runnable update) {
        int index = commands.getSelectionModel().getSelectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= commands.getItems().size()) return;
        BookActionCommand item = commands.getItems().remove(index);
        commands.getItems().add(target, item); commands.getSelectionModel().select(target); update.run();
    }

    private void showPreview(BookActionProfile profile) {
        Map<String,String> sample = new LinkedHashMap<>();
        Path sampleFile = Path.of(System.getProperty("user.home", "."), UiText.text("ui.actions.preview.sample_folder"), UiText.text("ui.actions.preview.sample_filename")).toAbsolutePath();
        sample.put("%FILE%", sampleFile.toString()); sample.put("%FILENAME%", sampleFile.getFileName().toString());
        sample.put("%DIR%", sampleFile.getParent().toString()); sample.put("%DEST%", sampleFile.getParent().toString());
        sample.put("%DESTFILE%", sampleFile.toString()); sample.put("%TMP%", System.getProperty("java.io.tmpdir", ""));
        sample.put("%TITLE%", UiText.text("ui.actions.preview.sample_title")); sample.put("%AUTHOR%", UiText.text("ui.actions.preview.sample_author")); sample.put("%SERIES%", UiText.text("ui.actions.preview.sample_series"));
        sample.put("%LANG%", "uk"); sample.put("%YEAR%", "2026"); sample.put("%ISBN%", "978-0-00-000000-0");
        sample.put("%PUBLISHER%", UiText.text("ui.actions.preview.sample_publisher")); sample.put("%EXT%", "fb2"); sample.put("%BOOKID%", "example-id");
        sample.put("%COLLECTION%", sampleFile.getParent().toString());
        BookActionPreview preview = executionService.preview(profile, sample);
        StringBuilder text = new StringBuilder(UiText.text("ui.java.book_action_profiles_dialog.f7b3900681"));
        int i = 1;
        for (BookActionPreview.PreviewCommand command : preview.commands()) {
            text.append(i++).append(UiText.text("ui.actions.preview.argv_tokens"));
            for (String arg : command.argv()) text.append("   [").append(arg).append("]\n");
            text.append(UiText.text("ui.actions.preview.cwd")).append(command.workingDirectory().isBlank() ? UiText.text("ui.actions.preview.default") : command.workingDirectory()).append('\n');
            text.append(UiText.text("ui.actions.preview.wait")).append(command.waitForExit() ? UiText.text("ui.common.yes") : UiText.text("ui.common.no")).append("\n\n");
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogLocalization.prepare(alert); alert.setTitle(UiText.text("ui.java.book_action_profiles_dialog.6859608a62")); alert.setHeaderText(profile.name());
        TextArea area = new TextArea(text.toString()); area.setEditable(false); area.setWrapText(false); area.setPrefSize(760, 420);
        alert.getDialogPane().setContent(area); alert.showAndWait();
    }

    private static final class MutableProfile {
        final String id; String name; boolean enabled; final ObservableList<BookActionCommand> commands;
        MutableProfile(BookActionProfile profile) {
            this.id = profile.id(); this.name = profile.name(); this.enabled = profile.enabled();
            this.commands = FXCollections.observableArrayList(profile.commands());
        }
        BookActionProfile toRecord() { return new BookActionProfile(id, name == null ? "" : name.trim(), enabled, List.copyOf(commands)); }
    }
}
