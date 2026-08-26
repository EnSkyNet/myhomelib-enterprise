package com.myhomelibcorp.ui.filter;

import com.myhomelibcorp.application.filter.BookFilterMode;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.query.book.BookFormat;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Reusable Stage 8 editor for the persisted global book filter. */
@Component
@RequiredArgsConstructor
public class BookFilterDialogService {
    private final BookFilterStateService filterStateService;
    private final LocalizationService i18n;

    public Optional<BookFilterSpec> show(Window owner) {
        BookFilterSpec current = filterStateService.current();
        Dialog<BookFilterSpec> dialog = new Dialog<>();
        dialog.setTitle(i18n.tr("Фільтри книг"));
        dialog.setHeaderText(i18n.tr("Єдиний фільтр для навігації, пошуку й таблиці"));
        if (owner != null) dialog.initOwner(owner);

        ButtonType apply = new ButtonType(i18n.tr("Застосувати"), ButtonBar.ButtonData.OK_DONE);
        ButtonType reset = new ButtonType(i18n.tr("Скинути фільтр"), ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(apply, reset, ButtonType.CANCEL);

        ComboBox<BookFilterMode> mode = new ComboBox<>();
        mode.getItems().setAll(BookFilterMode.values());
        mode.setConverter(new StringConverter<>() {
            @Override public String toString(BookFilterMode value) {
                return value == BookFilterMode.OR ? i18n.tr("Будь-яка умова (OR)") : i18n.tr("Усі умови (AND)");
            }
            @Override public BookFilterMode fromString(String value) { return BookFilterMode.AND; }
        });
        mode.setValue(current.mode());

        TextField language = field(current.language());
        TextField yearFrom = field(current.yearFrom());
        TextField yearTo = field(current.yearTo());
        ComboBox<String> format = new ComboBox<>();
        format.getItems().add(i18n.tr("Будь-який"));
        for (BookFormat value : BookFormat.values()) format.getItems().add(value.name());
        format.setValue(current.format() == null ? i18n.tr("Будь-який") : current.format().name());

        ComboBox<String> local = triState(i18n.tr("Будь-які"), i18n.tr("Тільки локальні"), i18n.tr("Тільки онлайн"), current.local());
        ComboBox<String> read = triState(i18n.tr("Будь-які"), i18n.tr("Прочитані"), i18n.tr("Непрочитані"), current.read());
        TextField ratingMin = field(current.ratingMin());
        TextField ratingMax = field(current.ratingMax());
        CheckBox hideUnrated = new CheckBox(i18n.tr("Приховати без оцінки"));
        hideUnrated.setSelected(current.hideUnrated());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(9); grid.setPadding(new Insets(12));
        int r = 0;
        grid.add(new Label(i18n.tr("Режим")), 0, r); grid.add(mode, 1, r++);
        grid.add(new Label(i18n.tr("Мова")), 0, r); grid.add(language, 1, r++);
        grid.add(new Label(i18n.tr("Рік від / до")), 0, r); grid.add(new HBox(6, yearFrom, yearTo), 1, r++);
        grid.add(new Label(i18n.tr("Формат")), 0, r); grid.add(format, 1, r++);
        grid.add(new Label(i18n.tr("Локальність")), 0, r); grid.add(local, 1, r++);
        grid.add(new Label(i18n.tr("Статус читання")), 0, r); grid.add(read, 1, r++);
        grid.add(new Label(i18n.tr("Оцінка від / до")), 0, r); grid.add(new HBox(6, ratingMin, ratingMax), 1, r++);
        grid.add(hideUnrated, 1, r);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button == reset) return BookFilterSpec.empty();
            if (button != apply) return null;
            return new BookFilterSpec(
                    mode.getValue(), text(language), integer(yearFrom), integer(yearTo), parseFormat(format.getValue()),
                    triBoolean(local.getSelectionModel().getSelectedIndex()),
                    triBoolean(read.getSelectionModel().getSelectedIndex()),
                    integer(ratingMin), integer(ratingMax), hideUnrated.isSelected(),
                    current.quickField(), current.quickValue());
        });
        Optional<BookFilterSpec> result = dialog.showAndWait();
        result.ifPresent(filterStateService::save);
        return result;
    }

    private ComboBox<String> triState(String any, String yes, String no, Boolean value) {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(any, yes, no);
        combo.getSelectionModel().select(value == null ? 0 : value ? 1 : 2);
        return combo;
    }
    private Boolean triBoolean(int index) { return index == 1 ? Boolean.TRUE : index == 2 ? Boolean.FALSE : null; }
    private TextField field(Object value) { return new TextField(value == null ? "" : value.toString()); }
    private String text(TextField f) { return f.getText() == null || f.getText().isBlank() ? null : f.getText().trim(); }
    private Integer integer(TextField f) {
        String v = text(f); if (v == null) return null;
        try { return Integer.valueOf(v); } catch (NumberFormatException ignored) { return null; }
    }
    private BookFormat parseFormat(String value) {
        try { return BookFormat.valueOf(value); } catch (Exception ignored) { return null; }
    }
}
