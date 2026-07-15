package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.domain.model.group.Group;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextInputDialog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DialogService {

    // ---- Інформаційні діалоги (з заголовком і текстом) ----
    public void showInfo(String title, String content) {
        showInfo(title, null, content);
    }

    public void showInfo(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
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
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // ---- Підтвердження ----
    public boolean showConfirmation(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    // ---- Текстове введення (4 аргументи) ----
    public Optional<String> showTextInput(String title, String header, String content, String defaultValue) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(content);
        return dialog.showAndWait();
    }

    // ---- Вибір зі списку (5 аргументів) ----
    public <T> Optional<T> showChoiceDialog(List<T> items, T defaultItem, String title, String header, String contentText) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        ChoiceDialog<T> dialog = new ChoiceDialog<>(defaultItem != null ? defaultItem : items.get(0), items);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(contentText);
        return dialog.showAndWait();
    }

    // ---- Спеціалізований вибір групи ----
    public Optional<Group> showGroupChoiceDialog(List<Group> groups, String bookTitle) {
        if (groups == null || groups.isEmpty()) {
            showWarning("Немає колекцій", "Створіть колекцію перед додаванням книги.");
            return Optional.empty();
        }
        return showChoiceDialog(
                groups,
                groups.get(0),
                "Додати до колекції",
                "Виберіть колекцію для книги '" + bookTitle + "'",
                "Колекція:"
        );
    }
}