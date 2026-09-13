package com.myhomelibcorp.ui.metadata;

import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.metadata.merge.ApplyMetadataCandidateUseCase;
import com.myhomelibcorp.application.metadata.merge.MetadataMergeField;
import com.myhomelibcorp.application.metadata.merge.MetadataMergePreview;
import com.myhomelibcorp.application.metadata.merge.MetadataReviewLookupResult;
import com.myhomelibcorp.application.metadata.merge.MetadataBatchService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.stage.Screen;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Reachable JavaFX workflow for explicit online metadata review and selective application. */
@Service
public class MetadataMergeUiService {
    private final MetadataBatchService batch;
    private final MetadataMergePresenter presenter;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final ApplicationState state;
    private final LocalizationService i18n;
    private final AtomicLong generation = new AtomicLong();

    public MetadataMergeUiService(MetadataBatchService batch, MetadataMergePresenter presenter,
                                  UiBackgroundExecutor background, DialogService dialogs,
                                  ApplicationState state, LocalizationService i18n) {
        this.batch = batch;
        this.presenter = presenter;
        this.background = background;
        this.dialogs = dialogs;
        this.state = state;
        this.i18n = i18n;
    }

    public void lookupAndReview(Window owner, com.myhomelibcorp.domain.model.valueobject.BookId bookId, Runnable onApplied) {
        if (bookId == null) return;
        lookupAndReview(owner, List.of(bookId), onApplied);
    }

    public void lookupAndReview(Window owner, List<BookId> bookIds, Runnable onApplied) {
        if (bookIds == null || bookIds.isEmpty()) {
            dialogs.showWarning(i18n.text("ui.metadata.title"), i18n.text("ui.metadata.select_books"));
            return;
        }
        if (bookIds.size() > MetadataBatchService.MAX_BOOKS) {
            dialogs.showWarning(i18n.text("ui.metadata.title"),
                    i18n.format("ui.metadata.batch_limit", MetadataBatchService.MAX_BOOKS));
            return;
        }
        var token = UiAsyncRequestGuard.next(generation, state);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        ProgressBar bar = new ProgressBar(0);
        Label count = new Label(i18n.format("ui.metadata.lookup_progress", 0, bookIds.size()));
        Dialog<ButtonType> progress = showProgress(owner, count, bar, finished, cancelled);
        batch.lookup(token.collectionId(), List.copyOf(bookIds), cancelled, update -> Platform.runLater(() -> {
            if (finished.get()) return;
            bar.setProgress((double) update.completed() / update.total());
            count.setText(i18n.format("ui.metadata.lookup_progress", update.completed(), update.total()));
        })).whenComplete((result, error) -> Platform.runLater(() -> {
            finished.set(true);
            progress.close();
            if (cancelled.get() || !UiAsyncRequestGuard.isCurrent(token, generation, state)) return;
            if (error != null) {
                dialogs.showError(i18n.text("ui.metadata.title"), UiExceptionMessages.root(error));
                return;
            }
            if (result == null || result.cancelled()) return;
            List<MetadataMergePreview> previews = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            for (var item : result.items()) {
                if (item.failed()) {
                    issues.add(item.bookId() + ": " + i18n.text("ui.metadata.book_failed"));
                    continue;
                }
                MetadataReviewLookupResult lookup = item.lookup();
                lookup.issues().forEach(issue -> issues.add(item.bookId() + " · " + issue.providerName()
                        + ": " + i18n.text("ui.metadata.issue." + issue.kind().name().toLowerCase(Locale.ROOT))));
                if (lookup.previews().isEmpty() || lookup.cancelled()) continue;
                MetadataMergePreview preview = result.items().size() == 1
                        ? chooseCandidate(owner, lookup.previews()) : lookup.previews().getFirst();
                if (preview != null) previews.add(preview);
            }
            if (!issues.isEmpty()) dialogs.showWarning(i18n.text("ui.metadata.title"), String.join("\n", issues));
            if (previews.isEmpty()) {
                if (issues.isEmpty()) dialogs.showWarning(i18n.text("ui.metadata.title"), i18n.text("ui.metadata.not_found"));
                return;
            }
            Optional<List<Selection>> selection = review(owner, previews);
            if (selection.isEmpty() || selection.get().isEmpty()) return;
            if (!UiAsyncRequestGuard.isCurrent(token, generation, state)) return;
            Map<String, Set<MetadataMergeField>> fields = new LinkedHashMap<>();
            selection.get().forEach(chosen -> fields.put(chosen.bookId(), chosen.fields()));
            List<ApplyMetadataCandidateUseCase.Request> requests = previews.stream()
                    .filter(preview -> fields.containsKey(preview.bookId().toString()))
                    .map(preview -> new ApplyMetadataCandidateUseCase.Request(preview.bookId(), preview.candidate(),
                            fields.get(preview.bookId().toString()))).toList();
            AtomicBoolean saveFinished = new AtomicBoolean();
            Dialog<ButtonType> saving = showProgress(owner,
                    new Label(i18n.format("ui.metadata.saving_progress", requests.size())),
                    new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS), saveFinished, cancelled);
            background.submit(() -> batch.apply(result.collectionId(), requests, cancelled))
                    .whenComplete((updated, saveError) -> Platform.runLater(() -> {
                        saveFinished.set(true);
                        saving.close();
                        if (!UiAsyncRequestGuard.isCurrent(token, generation, state)) return;
                        if (saveError != null) {
                            if (saveError instanceof java.util.concurrent.CancellationException
                                    || saveError.getCause() instanceof java.util.concurrent.CancellationException) return;
                            dialogs.showError(i18n.text("ui.metadata.title"), UiExceptionMessages.root(saveError));
                            return;
                        }
                        if (onApplied != null) onApplied.run();
                    }));
        }));
    }

    private Dialog<ButtonType> showProgress(Window owner, Label label, ProgressBar bar,
                                            AtomicBoolean finished, AtomicBoolean cancelled) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.metadata.title"));
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        bar.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(10, label, bar);
        content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);
        dialog.setOnCloseRequest(event -> { if (!finished.get()) cancelled.set(true); });
        dialog.show();
        return dialog;
    }

    /**
     * Single and batch preview share one UI contract. Returning empty means Cancel/no side effects.
     * The same explicit field selections are used for single-book and batch application.
     */
    public Optional<List<Selection>> review(Window owner, List<MetadataMergePreview> source) {
        List<MetadataMergePreview> safe = source == null ? List.of() : source.stream().filter(Objects::nonNull).toList();
        if (safe.isEmpty()) return Optional.empty();
        List<MetadataMergePresenter.Row> rows = presenter.present(safe);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(safe.size() == 1 ? "Перегляд онлайн-метаданих" : "Пакетний перегляд онлайн-метаданих");
        dialog.setHeaderText(safe.size() == 1
                ? safe.getFirst().source().providerName() + " · " + percent(safe.getFirst().confidence())
                : "Книг: " + safe.size() + " · виберіть поля для застосування");
        if (owner != null) dialog.initOwner(owner);
        ButtonType applyButtonType = new ButtonType("Застосувати вибране", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButtonType, ButtonType.CANCEL);

        VBox content = new VBox(10);
        content.setPadding(new Insets(8));
        Map<String, EnumSet<MetadataMergeField>> selected = new LinkedHashMap<>();
        Map<String, MetadataMergePreview> byBook = new LinkedHashMap<>();
        for (MetadataMergePreview preview : safe) {
            if (byBook.putIfAbsent(preview.bookId().toString(), preview) != null) {
                throw new IllegalArgumentException("Select one candidate per book before review");
            }
        }

        String previousBook = null;
        for (MetadataMergePresenter.Row row : rows) {
            if (!Objects.equals(previousBook, row.bookId())) {
                Label bookHeader = new Label(row.bookTitle() + "  ·  " + row.source() + "  ·  " + row.confidence());
                bookHeader.getStyleClass().add("section-title");
                content.getChildren().add(bookHeader);
                previousBook = row.bookId();
            }
            CheckBox check = new CheckBox(row.fieldLabel());
            check.setWrapText(true);
            check.setMinWidth(0);
            check.setDisable(!row.changed());
            check.setSelected(false);
            Label current = valueLabel(row.currentValue());
            Label proposed = valueLabel(row.proposedValue());
            GridPane line = comparisonLine(check, current, proposed);
            content.getChildren().add(line);
            check.selectedProperty().addListener((obs, oldValue, newValue) -> {
                EnumSet<MetadataMergeField> fields = selected.computeIfAbsent(row.bookId(), ignored -> EnumSet.noneOf(MetadataMergeField.class));
                if (Boolean.TRUE.equals(newValue)) fields.add(row.field()); else fields.remove(row.field());
                updateApplyDisabled(dialog, applyButtonType, selected);
            });
        }

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        Rectangle2D bounds = screenBounds(owner);
        scroll.setPrefViewportWidth(Math.min(900, bounds.getWidth() * 0.85));
        scroll.setPrefViewportHeight(Math.min(bounds.getHeight() * 0.7, Math.min(640, 170 + rows.size() * 46)));
        dialog.getDialogPane().setContent(scroll);
        dialog.setResizable(true);
        updateApplyDisabled(dialog, applyButtonType, selected);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != applyButtonType) return Optional.empty();
        List<Selection> result = new ArrayList<>();
        for (Map.Entry<String, EnumSet<MetadataMergeField>> entry : selected.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            MetadataMergePreview preview = byBook.get(entry.getKey());
            if (preview != null) result.add(new Selection(preview.bookId().toString(), Set.copyOf(entry.getValue())));
        }
        return Optional.of(List.copyOf(result));
    }

    private MetadataMergePreview chooseCandidate(Window owner, List<MetadataMergePreview> candidates) {
        if (candidates.size() == 1) return candidates.getFirst();
        List<CandidateChoice> choices = candidates.stream().map(CandidateChoice::new).toList();
        ChoiceDialog<CandidateChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        dialog.setTitle("Онлайн-метадані");
        dialog.setHeaderText("Знайдено варіантів: " + choices.size());
        dialog.setContentText("Оберіть джерело:");
        if (owner != null) dialog.initOwner(owner);
        return dialog.showAndWait().map(CandidateChoice::preview).orElse(null);
    }

    private static GridPane comparisonLine(CheckBox field, Label current, Label proposed) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);
        grid.setPadding(new Insets(2, 0, 4, 8));
        grid.getColumnConstraints().addAll(column(20, Priority.NEVER), column(40, Priority.ALWAYS), column(40, Priority.ALWAYS));
        grid.add(field, 0, 0);
        grid.add(new Label("Поточне"), 1, 0);
        grid.add(new Label("Запропоноване"), 2, 0);
        grid.add(current, 1, 1);
        grid.add(proposed, 2, 1);
        return grid;
    }

    private static Rectangle2D screenBounds(Window owner) {
        if (owner != null) {
            var screens = Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight());
            if (!screens.isEmpty()) return screens.getFirst().getVisualBounds();
        }
        return Screen.getPrimary().getVisualBounds();
    }

    private static ColumnConstraints column(double percentage, Priority grow) {
        ColumnConstraints c = new ColumnConstraints();
        c.setMinWidth(0);
        c.setPercentWidth(percentage);
        c.setHgrow(grow);
        return c;
    }

    private static Label valueLabel(String value) {
        Label label = new Label(value == null || value.isBlank() ? "—" : value);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static void updateApplyDisabled(Dialog<ButtonType> dialog, ButtonType applyButton,
                                            Map<String, EnumSet<MetadataMergeField>> selected) {
        Node button = dialog.getDialogPane().lookupButton(applyButton);
        if (button != null) button.setDisable(selected.values().stream().allMatch(Set::isEmpty));
    }

    private static String percent(double confidence) {
        return String.format(Locale.ROOT, "%.1f%%", confidence * 100.0);
    }

    public record Selection(String bookId, Set<MetadataMergeField> fields) {
        public Selection {
            bookId = bookId == null ? "" : bookId;
            fields = fields == null ? Set.of() : Set.copyOf(fields);
        }
    }

    private record CandidateChoice(MetadataMergePreview preview) {
        @Override
        public String toString() {
            MetadataCandidate candidate = preview.candidate();
            String authors = candidate.authors().isEmpty() ? "" : " — " + String.join(", ", candidate.authors());
            return candidate.title() + authors + " · " + candidate.source().providerName() + " · " + percent(candidate.confidence());
        }
    }
}
