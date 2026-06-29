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

    /**
     * Показує інформаційне повідомлення.
     */
    public void showInfo(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    /**
     * Показує попередження.
     */
    public void showWarning(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    /**
     * Показує помилку.
     */
    public void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    /**
     * Показує діалог підтвердження.
     * @return true якщо користувач натиснув OK, false якщо Cancel.
     */
    public boolean showConfirmation(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    /**
     * Показує діалог введення тексту.
     * @return Optional з введеним текстом, або empty якщо скасовано.
     */
    public Optional<String> showTextInput(String title, String header, String content, String defaultValue) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(content);
        return dialog.showAndWait();
    }

    /**
     * Показує діалог вибору зі списку.
     * @param items список елементів для вибору
     * @param title заголовок діалогу
     * @param header текст заголовка
     * @param contentText текст підказки
     * @return Optional з вибраним елементом, або empty якщо скасовано.
     */
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

    /**
     * Показує діалог вибору групи для книги.
     */
    public Optional<Group> showGroupChoiceDialog(List<Group> groups, String bookTitle) {
        if (groups == null || groups.isEmpty()) {
            showWarning("Увага", "Немає груп", "Створіть групу перед додаванням книг.");
            return Optional.empty();
        }
        return showChoiceDialog(
                groups,
                groups.get(0),
                "Додати до групи",
                "Виберіть групу для книги '" + bookTitle + "'",
                "Група:"
        );
    }
}