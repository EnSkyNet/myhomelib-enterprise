package com.myhomelibcorp.ui.service;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextInputDialog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Service
public class DialogService {

    // ---- Інформаційні діалоги (з заголовком і текстом) ----
    public void showInfo(String title, String content) {
        showInfo(title, null, content);
    }

    public void showInfo(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogLocalization.prepare(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // ---- Попередження ----
    public void showWarning(String title, String content) {
        showWarning(title, null, content);
    }

    public void showWarning(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        DialogLocalization.prepare(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // ---- Помилки ----
    public void showError(String title, String content) {
        showError(title, null, content);
    }

    public void showError(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogLocalization.prepare(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }


    /** Error dialog with an explicit Retry action. */
    public boolean showErrorWithRetry(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogLocalization.prepare(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        ButtonType retry = new ButtonType(UiText.text("ui.java.dialog_service.c95c5c27bb"), ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType(UiText.text("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(retry, close);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == retry;
    }

    // ---- Підтвердження ----
    public boolean showConfirmation(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogLocalization.prepare(alert);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    // ---- Текстове введення (4 аргументи) ----
    public Optional<String> showTextInput(String title, String header, String content, String defaultValue) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        DialogLocalization.prepare(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(content);
        return dialog.showAndWait();
    }

    // ---- Вибір зі списку (5 аргументів) ----
    public <T> Optional<T> showChoiceDialog(List<T> items, T defaultItem, String title, String header, String contentText) {
        return showChoiceDialog(items, defaultItem, title, header, contentText, DialogService::simpleChoiceLabel);
    }

    /**
     * Choice dialog with an explicit user-facing renderer. Domain/DTO objects must use this overload
     * instead of relying on their debug-oriented {@code toString()} implementation.
     */
    public <T> Optional<T> showChoiceDialog(List<T> items, T defaultItem, String title, String header,
                                             String contentText, Function<? super T, String> labeler) {
        if (items == null || items.isEmpty()) return Optional.empty();
        if (labeler == null) {
            throw new IllegalArgumentException("A user-facing choice labeler is required for object choices");
        }
        Function<? super T, String> effectiveLabeler = labeler;
        List<ChoiceItem<T>> choices = items.stream()
                .map(item -> new ChoiceItem<>(item, safeChoiceLabel(effectiveLabeler.apply(item))))
                .toList();
        T selectedDefault = defaultItem != null ? defaultItem : items.get(0);
        ChoiceItem<T> defaultChoice = choices.stream()
                .filter(choice -> java.util.Objects.equals(choice.value(), selectedDefault))
                .findFirst()
                .orElse(choices.get(0));
        ChoiceDialog<ChoiceItem<T>> dialog = new ChoiceDialog<>(defaultChoice, choices);
        DialogLocalization.prepare(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(contentText);
        return dialog.showAndWait().map(ChoiceItem::value);
    }

    private static String simpleChoiceLabel(Object value) {
        if (value == null) return "";
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Character) {
            return value.toString();
        }
        throw new IllegalArgumentException(
                "Object choices require an explicit user-facing labeler: " + value.getClass().getName());
    }

    private static String safeChoiceLabel(String label) {
        return label == null ? "" : label;
    }

    private record ChoiceItem<T>(T value, String label) {
        @Override public String toString() { return label; }
    }

    // ---- Спеціалізований вибір групи ----


}