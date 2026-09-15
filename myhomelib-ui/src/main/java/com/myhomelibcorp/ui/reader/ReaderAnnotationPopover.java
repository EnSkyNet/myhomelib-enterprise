package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderAnnotationActivation;
import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Lightweight Reader details popup for an existing highlight/note. */
@Component
@RequiredArgsConstructor
public class ReaderAnnotationPopover {
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final LocalizationService i18n;
    private Popup current;

    public void show(Window owner, ReaderAnnotationActivation activation, Actions actions) {
        if (owner == null || activation == null || activation.annotation() == null) return;
        hide();
        ReaderAnnotationOverlay annotation = activation.annotation();

        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setAutoFix(true);

        Label type = new Label(annotation.note()
                ? i18n.text("ui.annotations.type.note")
                : i18n.text("ui.annotations.type.highlight"));
        type.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label state = new Label(annotation.relocated()
                ? i18n.text("ui.reader.annotation.state.relocated")
                : i18n.text("ui.reader.annotation.state.resolved"));
        state.setStyle(annotation.relocated()
                ? "-fx-text-fill: -mhl-warning;"
                : "-fx-text-fill: -mhl-muted-text;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label updated = new Label(annotation.updatedAt().equals(java.time.Instant.EPOCH)
                ? ""
                : UPDATED.format(annotation.updatedAt().atZone(ZoneId.systemDefault())));
        updated.setStyle("-fx-text-fill: -mhl-muted-text;");
        HBox header = new HBox(8, type, state, spacer, updated);
        header.setAlignment(Pos.CENTER_LEFT);

        Label quoteTitle = new Label(i18n.text("ui.reader.annotation.editor.quote"));
        quoteTitle.setStyle("-fx-font-weight: bold;");
        Label quote = new Label(annotation.quote());
        quote.setWrapText(true);
        quote.setMaxWidth(500);
        quote.setStyle("-fx-font-style: italic;");

        VBox body = new VBox(5, quoteTitle, quote);
        if (!annotation.chapterTitle().isBlank()) {
            Label chapter = new Label(annotation.chapterTitle());
            chapter.setWrapText(true);
            chapter.setStyle("-fx-text-fill: -mhl-muted-text; -fx-font-weight: bold;");
            body.getChildren().add(0, chapter);
        }
        if (annotation.note() && !annotation.noteText().isBlank()) {
            Label noteTitle = new Label(i18n.text("ui.reader.annotation.editor.note"));
            noteTitle.setStyle("-fx-font-weight: bold;");
            Label note = new Label(annotation.noteText());
            note.setWrapText(true);
            note.setMaxWidth(500);
            body.getChildren().addAll(noteTitle, note);
        }

        FlowPane tags = new FlowPane(6, 4);
        annotation.tags().stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(tag -> {
            Label chip = new Label("#" + tag);
            chip.setStyle("-fx-background-color: -mhl-hover; -fx-background-radius: 10; -fx-padding: 2 7;");
            tags.getChildren().add(chip);
        });
        tags.setManaged(!annotation.tags().isEmpty());
        tags.setVisible(!annotation.tags().isEmpty());

        Button edit = actionButton("ui.annotations.edit", actions.edit(), popup, actions.focusReader());
        Button copyQuote = actionButton("ui.reader.annotation.copy_quote", actions.copyQuote(), popup, actions.focusReader());
        Button copyAll = actionButton("ui.reader.annotation.copy_quote_note", actions.copyQuoteAndNote(), popup, actions.focusReader());
        Button delete = actionButton("ui.annotations.delete", actions.delete(), popup, actions.focusReader());
        Button reanchor = actionButton("ui.reader.annotation.reanchor", actions.reanchor(), popup, actions.focusReader());
        reanchor.setManaged(annotation.relocated());
        reanchor.setVisible(annotation.relocated());
        Button manager = actionButton("ui.reader.annotation.open_manager", actions.openManager(), popup, null);
        FlowPane actionRow = new FlowPane(7, 7);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.getChildren().addAll(edit, copyQuote, copyAll, reanchor, delete, manager);

        VBox card = new VBox(10, header, body, tags, actionRow);
        card.setFocusTraversable(true);
        card.setPadding(new Insets(12));
        card.setPrefWidth(540);
        card.setMinWidth(360);
        card.setMaxWidth(620);
        card.setStyle("-fx-background-color: -mhl-panel; -fx-border-color: -mhl-border; "
                + "-fx-border-radius: 8; -fx-background-radius: 8; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 16, 0.18, 0, 4);");
        popup.getContent().add(card);
        current = popup;
        popup.show(owner, activation.screenX() + 8, activation.screenY() + 8);
        card.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                popup.hide();
                if (actions.focusReader() != null) Platform.runLater(actions.focusReader());
                event.consume();
            }
        });
        Platform.runLater(edit::requestFocus);
    }

    public void hide() {
        Popup popup = current;
        current = null;
        if (popup != null) popup.hide();
    }

    private Button actionButton(String key, Runnable action, Popup popup, Runnable focusAfter) {
        Button button = new Button(i18n.text(key));
        button.setOnAction(event -> {
            popup.hide();
            if (action != null) action.run();
            if (focusAfter != null) Platform.runLater(focusAfter);
        });
        return button;
    }

    public record Actions(
            Runnable edit,
            Runnable copyQuote,
            Runnable copyQuoteAndNote,
            Runnable delete,
            Runnable reanchor,
            Runnable openManager,
            Runnable focusReader
    ) { }
}
