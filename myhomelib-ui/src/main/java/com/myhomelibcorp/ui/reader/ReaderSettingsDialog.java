package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderTheme;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/** Невеликий native JavaFX dialog без FXML — зручно перевикористати на desktop. */
final class ReaderSettingsDialog {

    private ReaderSettingsDialog() {
    }

    static Optional<ReaderSettings> show(Window owner, ReaderSettings current) {
        ReaderSettings s = current != null ? current : ReaderSettings.defaultSettings();

        Dialog<ReaderSettings> dialog = new Dialog<>();
        dialog.setTitle("Налаштування читання");
        dialog.setHeaderText("Вигляд та поведінка читалки");
        if (owner != null) dialog.initOwner(owner);

        ButtonType apply = new ButtonType("Застосувати", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        ComboBox<String> theme = new ComboBox<>(FXCollections.observableArrayList("light", "sepia", "dark", "amoled"));
        theme.setValue(s.themeName());

        ReaderTheme effectiveTheme = ReaderTheme.fromSettings(s);
        ColorPicker backgroundColor = new ColorPicker(Color.web(effectiveTheme.background()));
        ColorPicker textColor = new ColorPicker(Color.web(effectiveTheme.foreground()));
        theme.setOnAction(event -> {
            ReaderTheme selectedTheme = ReaderTheme.fromName(theme.getValue());
            backgroundColor.setValue(Color.web(selectedTheme.background()));
            textColor.setValue(Color.web(selectedTheme.foreground()));
        });

        List<String> families = Font.getFamilies();
        ComboBox<String> fontFamily = new ComboBox<>(FXCollections.observableArrayList(families));
        fontFamily.setEditable(true);
        fontFamily.setValue(s.fontFamily());
        fontFamily.setPrefWidth(220);

        Spinner<Double> fontSize = doubleSpinner(10, 52, s.fontSize(), 0.5);
        Spinner<Double> lineSpacing = doubleSpinner(1.0, 2.5, s.lineSpacing(), 0.05);
        Spinner<Double> paragraphSpacing = doubleSpinner(0, 4, s.paragraphSpacing(), 0.1);
        Spinner<Double> indent = doubleSpinner(0, 4, s.firstLineIndent(), 0.1);
        Spinner<Double> left = doubleSpinner(0, 120, s.leftMargin(), 1);
        Spinner<Double> right = doubleSpinner(0, 120, s.rightMargin(), 1);
        Spinner<Double> top = doubleSpinner(0, 120, s.topMargin(), 1);
        Spinner<Double> bottom = doubleSpinner(0, 120, s.bottomMargin(), 1);

        ComboBox<String> alignment = new ComboBox<>(FXCollections.observableArrayList("left", "justify", "center"));
        alignment.setValue(s.alignment());

        CheckBox hyphenation = new CheckBox("Переноси слів");
        hyphenation.setSelected(s.hyphenation());
        CheckBox pageMode = new CheckBox("Показувати номер сторінки");
        pageMode.setSelected(s.pageMode());
        CheckBox autoScroll = new CheckBox("Автопрокрутка");
        autoScroll.setSelected(s.autoScroll());
        Spinner<Integer> scrollSpeed = new Spinner<>(1, 5, Math.max(1, Math.min(5, s.scrollSpeed())));
        CheckBox showToolbar = new CheckBox("Показувати панель інструментів");
        showToolbar.setSelected(s.showToolbar());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        int r = 0;
        row(grid, r++, "Тема", theme);
        row(grid, r++, "Колір фону", backgroundColor);
        row(grid, r++, "Колір тексту", textColor);
        row(grid, r++, "Шрифт", fontFamily);
        row(grid, r++, "Розмір", fontSize);
        row(grid, r++, "Міжрядковий інтервал", lineSpacing);
        row(grid, r++, "Відстань між абзацами", paragraphSpacing);
        row(grid, r++, "Відступ першого рядка (em)", indent);
        row(grid, r++, "Вирівнювання", alignment);
        row(grid, r++, "Поле ліворуч", left);
        row(grid, r++, "Поле праворуч", right);
        row(grid, r++, "Поле зверху", top);
        row(grid, r++, "Поле знизу", bottom);
        row(grid, r++, "Швидкість автопрокрутки", scrollSpeed);
        grid.add(hyphenation, 0, r++, 2, 1);
        grid.add(pageMode, 0, r++, 2, 1);
        grid.add(autoScroll, 0, r++, 2, 1);
        grid.add(showToolbar, 0, r, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(520);

        dialog.setResultConverter(button -> {
            if (button != apply) return null;
            String family = fontFamily.getEditor().getText();
            if (family == null || family.isBlank()) family = "Georgia";
            return new ReaderSettings(
                    theme.getValue(),
                    family,
                    fontSize.getValue(),
                    lineSpacing.getValue(),
                    paragraphSpacing.getValue(),
                    indent.getValue(),
                    alignment.getValue(),
                    left.getValue(),
                    right.getValue(),
                    top.getValue(),
                    bottom.getValue(),
                    hyphenation.isSelected(),
                    pageMode.isSelected(),
                    autoScroll.isSelected(),
                    scrollSpeed.getValue(),
                    showToolbar.isSelected(),
                    mergeReaderColors(s.customCss(), theme.getValue(), backgroundColor.getValue(), textColor.getValue())
            );
        });

        return dialog.showAndWait();
    }


    private static String mergeReaderColors(String css, String themeName, Color background, Color foreground) {
        String result = css == null ? "" : css;
        result = result.replaceAll("(?i)--reader-background\\s*:\\s*#[0-9a-f]{6,8}\\s*;?", "")
                .replaceAll("(?i)--reader-foreground\\s*:\\s*#[0-9a-f]{6,8}\\s*;?", "")
                .trim();

        ReaderTheme base = ReaderTheme.fromName(themeName);
        String bg = toHex(background);
        String fg = toHex(foreground);
        if (bg.equalsIgnoreCase(base.background()) && fg.equalsIgnoreCase(base.foreground())) {
            return result;
        }

        String variables = "--reader-background: " + bg + "; "
                + "--reader-foreground: " + fg + ";";
        return result.isBlank() ? variables : result + System.lineSeparator() + variables;
    }

    private static String toHex(Color color) {
        Color c = color != null ? color : Color.BLACK;
        return String.format("#%02X%02X%02X",
                Math.round(c.getRed() * 255),
                Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
    }

    private static Spinner<Double> doubleSpinner(double min, double max, double value, double step) {
        Spinner<Double> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max,
                Math.max(min, Math.min(max, value)), step));
        spinner.setEditable(true);
        spinner.setPrefWidth(120);
        return spinner;
    }

    private static void row(GridPane grid, int row, String label, Node control) {
        grid.add(new Label(label + ':'), 0, row);
        grid.add(control, 1, row);
    }
}
