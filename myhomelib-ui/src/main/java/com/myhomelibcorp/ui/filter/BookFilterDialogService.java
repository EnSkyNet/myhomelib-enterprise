package com.myhomelibcorp.ui.filter;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.application.filter.BookFilterMode;
import com.myhomelibcorp.application.filter.BookAnnotationPresenceFilter;
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
        Dialog<BookFilterSpec> dialog = createDialog(owner, filterStateService.current());
        Optional<BookFilterSpec> result = dialog.showAndWait();
        result.ifPresent(filterStateService::save);
        return result;
    }

    /** Builds the dialog without showing it so display-capable UI tests can inspect the real control tree. */
    Dialog<BookFilterSpec> createDialog(Window owner, BookFilterSpec current) {
        final BookFilterSpec effectiveCurrent = current == null ? BookFilterSpec.empty() : current;
        Dialog<BookFilterSpec> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.text.book_filters"));
        dialog.setHeaderText(i18n.text("ui.text.unified_filter_for_navigation_search_and_table"));
        if (owner != null) dialog.initOwner(owner);

        ButtonType apply = new ButtonType(i18n.text("ui.text.apply"), ButtonBar.ButtonData.OK_DONE);
        ButtonType reset = new ButtonType(i18n.text("ui.text.reset_filter"), ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(apply, reset, ButtonType.CANCEL);

        ComboBox<BookFilterMode> mode = new ComboBox<>();
        mode.getItems().setAll(BookFilterMode.values());
        mode.setConverter(new StringConverter<>() {
            @Override public String toString(BookFilterMode value) {
                return value == BookFilterMode.OR ? i18n.text("ui.text.any_condition_or") : i18n.text("ui.text.all_conditions_and");
            }
            @Override public BookFilterMode fromString(String value) {
                if (value == null || value.isBlank()) return BookFilterMode.AND;
                String normalized = value.trim();
                if (normalized.equalsIgnoreCase(i18n.text("ui.text.any_condition_or")) || normalized.equalsIgnoreCase("OR")) return BookFilterMode.OR;
                return BookFilterMode.AND;
            }
        });
        mode.setValue(effectiveCurrent.mode());

        TextField language = field(effectiveCurrent.language());
        TextField yearFrom = field(effectiveCurrent.yearFrom());
        TextField yearTo = field(effectiveCurrent.yearTo());
        ComboBox<String> format = new ComboBox<>();
        format.getItems().add(i18n.text("ui.text.any"));
        for (BookFormat value : BookFormat.values()) format.getItems().add(value.name());
        format.setValue(effectiveCurrent.format() == null ? i18n.text("ui.text.any") : effectiveCurrent.format().name());

        ComboBox<String> local = triState(i18n.text("ui.text.any_2ce12a5"), i18n.text("ui.text.local_only"), i18n.text("ui.text.online_only"), effectiveCurrent.local());
        ComboBox<String> read = triState(i18n.text("ui.text.any_2ce12a5"), i18n.text("ui.text.read"), i18n.text("ui.text.unread"), effectiveCurrent.read());
        ComboBox<BookAnnotationPresenceFilter> annotationPresence = new ComboBox<>();
        annotationPresence.setId("annotationPresenceFilter");
        annotationPresence.setAccessibleText(i18n.text("ui.filter.annotation.label"));
        annotationPresence.getItems().setAll(BookAnnotationPresenceFilter.values());
        annotationPresence.setConverter(new StringConverter<>() {
            @Override public String toString(BookAnnotationPresenceFilter value) {
                if (value == null) return i18n.text("ui.filter.annotation.any");
                return switch (value) {
                    case ANY -> i18n.text("ui.filter.annotation.any");
                    case NOTES -> i18n.text("ui.filter.annotation.notes");
                    case HIGHLIGHTS -> i18n.text("ui.filter.annotation.highlights");
                    case NOTES_OR_HIGHLIGHTS -> i18n.text("ui.filter.annotation.any_annotation");
                };
            }
            @Override public BookAnnotationPresenceFilter fromString(String value) { return BookAnnotationPresenceFilter.ANY; }
        });
        annotationPresence.setValue(effectiveCurrent.annotationPresence());
        TextField ratingMin = field(effectiveCurrent.ratingMin());
        TextField ratingMax = field(effectiveCurrent.ratingMax());
        CheckBox hideUnrated = new CheckBox(i18n.text("ui.text.hide_unrated"));
        hideUnrated.setSelected(effectiveCurrent.hideUnrated());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(9); grid.setPadding(new Insets(12));
        int r = 0;
        grid.add(new Label(i18n.text("ui.text.mode")), 0, r); grid.add(mode, 1, r++);
        grid.add(new Label(i18n.text("ui.text.language")), 0, r); grid.add(language, 1, r++);
        grid.add(new Label(i18n.text("ui.text.year_from_to")), 0, r); grid.add(new HBox(6, yearFrom, yearTo), 1, r++);
        grid.add(new Label(i18n.text("ui.text.format")), 0, r); grid.add(format, 1, r++);
        grid.add(new Label(i18n.text("ui.text.availability")), 0, r); grid.add(local, 1, r++);
        grid.add(new Label(i18n.text("ui.text.reading_status")), 0, r); grid.add(read, 1, r++);
        grid.add(new Label(i18n.text("ui.filter.annotation.label")), 0, r); grid.add(annotationPresence, 1, r++);
        grid.add(new Label(i18n.text("ui.text.rating_from_to")), 0, r); grid.add(new HBox(6, ratingMin, ratingMax), 1, r++);
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
                    annotationPresence.getValue(), effectiveCurrent.quickField(), effectiveCurrent.quickValue());
        });
        return dialog;
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
