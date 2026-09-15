package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.annotation.AnnotationAnchorData;
import com.myhomelibcorp.application.annotation.AnnotationManagerType;
import com.myhomelibcorp.application.annotation.AnnotationService;
import com.myhomelibcorp.reader.api.ReaderAnnotationActivation;
import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderSelection;
import com.myhomelibcorp.ui.annotation.AnnotationEditorDialog;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Owns the complete interactive Reader annotation workflow.
 *
 * <p>The Reader controller supplies only a short-lived immutable context for the currently open
 * book. This keeps dialogs, persistence commands, clipboard actions and safe re-anchoring out of
 * the already large workspace controller while preventing stale async completions from mutating a
 * newly opened book.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderAnnotationCoordinator {
    private static final int CONTEXT_CHARS = 48;

    private final AnnotationService annotationService;
    private final AnnotationEditorDialog editorDialog;
    private final ReaderAnnotationPopover popover;
    private final UiBackgroundExecutor uiBackgroundExecutor;
    private final DialogService dialogService;
    private final LocalizationService i18n;

    public void createHighlight(Context context, ReaderSelection selection) {
        if (!usable(context) || selection == null || selection.text() == null || selection.text().isBlank()) return;
        AnnotationAnchorData anchor = ReaderAnnotationPresenter.anchor(context.bookId(), context.artifactId(), selection);
        uiBackgroundExecutor.submit(() -> annotationService.createHighlight(
                        anchor, AnnotationService.DEFAULT_COLOR, Set.of()))
                .thenAccept(saved -> complete(context, true, "ui.reader.annotation.highlight_saved"))
                .exceptionally(error -> fail(context, error));
    }

    public void createNote(Context context, ReaderSelection selection) {
        if (!usable(context) || selection == null || selection.text() == null || selection.text().isBlank()) return;
        AnnotationEditorDialog.Result edited = editorDialog.showCreateNote(
                context.owner(), selection.text(), AnnotationService.DEFAULT_COLOR, Set.of(), context.knownTags())
                .orElse(null);
        if (edited == null) {
            focus(context);
            return;
        }

        AnnotationAnchorData anchor = ReaderAnnotationPresenter.anchor(context.bookId(), context.artifactId(), selection);
        uiBackgroundExecutor.submit(() -> annotationService.createNote(
                        anchor, edited.color(), edited.note(), edited.tags()))
                .thenAccept(saved -> complete(context, true, "ui.reader.annotation.note_saved"))
                .exceptionally(error -> fail(context, error));
    }

    public void showPopover(Context context, ReaderAnnotationActivation activation) {
        if (!usable(context) || activation == null || activation.annotation() == null) return;
        ReaderAnnotationOverlay annotation = activation.annotation();
        popover.show(context.owner(), activation, new ReaderAnnotationPopover.Actions(
                () -> edit(context, annotation),
                () -> copyToClipboard(annotation.quote()),
                () -> copyToClipboard(quoteAndNote(annotation)),
                () -> delete(context, annotation),
                () -> reanchor(context, annotation),
                context.openManager(),
                context.focusReader()));
    }

    public void edit(Context context, ReaderAnnotationOverlay annotation) {
        if (!usable(context) || annotation == null) return;
        AnnotationManagerType type = annotation.note() ? AnnotationManagerType.NOTE : AnnotationManagerType.HIGHLIGHT;
        AnnotationEditorDialog.Result edited = editorDialog.showEdit(
                        context.owner(), type, annotation.quote(), annotation.noteText(), annotation.color(),
                        annotation.tags(), context.knownTags())
                .orElse(null);
        if (edited == null) {
            focus(context);
            return;
        }

        uiBackgroundExecutor.submit(() -> annotationService.update(
                        annotation.id(), edited.note(), edited.color(), edited.tags()))
                .thenAccept(saved -> complete(context, false, "ui.reader.annotation.updated"))
                .exceptionally(error -> fail(context, error));
    }

    public void delete(Context context, ReaderAnnotationOverlay annotation) {
        if (!usable(context) || annotation == null) return;
        if (!dialogService.showConfirmation(
                i18n.text("ui.annotations.delete"),
                i18n.text("ui.reader.annotation.delete_header"),
                annotation.displayText())) {
            focus(context);
            return;
        }

        uiBackgroundExecutor.submit(() -> {
            annotationService.delete(annotation.id());
            return null;
        }).thenAccept(ignored -> complete(context, false, "ui.reader.annotation.deleted"))
                .exceptionally(error -> fail(context, error));
    }

    public void reanchor(Context context, ReaderAnnotationOverlay annotation) {
        if (!usable(context) || annotation == null || !annotation.relocated()) return;
        if (!dialogService.showConfirmation(
                i18n.text("ui.reader.annotation.reanchor"),
                i18n.text("ui.reader.annotation.reanchor_header"),
                i18n.text("ui.reader.annotation.reanchor_confirm"))) {
            focus(context);
            return;
        }

        AnnotationAnchorData anchor = anchorForRange(
                context, annotation.startOffset(), annotation.endOffset(), annotation.quote());
        if (anchor == null) {
            focus(context);
            return;
        }
        persistAnchor(context, annotation.id(), anchor);
    }

    public void rebind(Context context, ReaderAnnotationUnavailable issue) {
        if (!usable(context) || issue == null || !issue.canOfferSafeRebind()) return;
        var candidate = issue.rebindCandidate();
        if (!dialogService.showConfirmation(
                i18n.text("ui.reader.annotation.reanchor"),
                i18n.text("ui.reader.annotation.rebind_header"),
                i18n.format("ui.reader.annotation.rebind_confirm",
                        candidate.occurrenceCount(), candidate.contextScore()))) {
            focus(context);
            return;
        }

        AnnotationAnchorData anchor = anchorForRange(
                context, candidate.startOffset(), candidate.endOffset(),
                issue.source() == null ? "" : issue.source().anchor().quote());
        if (anchor == null) {
            focus(context);
            return;
        }
        persistAnchor(context, issue.id(), anchor);
    }

    public void hidePopover() {
        popover.hide();
    }

    private void persistAnchor(Context context, String annotationId, AnnotationAnchorData anchor) {
        uiBackgroundExecutor.submit(() -> annotationService.reanchor(annotationId, anchor))
                .thenAccept(saved -> complete(context, false, "ui.reader.annotation.reanchored"))
                .exceptionally(error -> fail(context, error));
    }

    private AnnotationAnchorData anchorForRange(
            Context context, long requestedStart, long requestedEnd, String preferredQuote) {
        ReaderDocument document = context.document();
        if (document == null || document.text() == null) return null;
        long total = Math.max(0L, document.totalTextLength());
        if (total == 0L) return null;
        long start = Math.max(0L, Math.min(requestedStart, total));
        long end = Math.max(start, Math.min(requestedEnd, total));
        int chapterIndex = Math.max(0, document.chapterIndexAt(start));
        var chapter = document.chapter(chapterIndex);
        int prefixStart = Math.toIntExact(Math.max(0L, start - CONTEXT_CHARS));
        int suffixEnd = Math.toIntExact(Math.min(total, end + CONTEXT_CHARS));
        String prefix = document.text().getText(prefixStart, Math.toIntExact(start));
        String suffix = document.text().getText(Math.toIntExact(end), suffixEnd);
        String quote = preferredQuote == null || preferredQuote.isBlank()
                ? document.text().getText(Math.toIntExact(start), Math.toIntExact(end))
                : preferredQuote;
        return new AnnotationAnchorData(
                context.bookId(), context.artifactId(),
                chapter == null ? null : chapter.id(), chapter == null ? null : chapter.title(), null,
                start, end, Math.max(0.0, Math.min(1.0, start / (double) total)), quote, prefix, suffix);
    }

    private void complete(Context context, boolean clearSelection, String statusKey) {
        Platform.runLater(() -> {
            if (!current(context)) return;
            if (clearSelection && context.clearSelection() != null) context.clearSelection().run();
            if (context.status() != null) context.status().accept(i18n.text(statusKey));
            if (context.refresh() != null) context.refresh().run();
            focus(context);
        });
    }

    private static void focus(Context context) {
        if (context != null && context.focusReader() != null) Platform.runLater(context.focusReader());
    }

    private <T> T fail(Context context, Throwable error) {
        Throwable root = rootCause(error);
        log.error("Не вдалося виконати дію з анотацією для книги {}", context == null ? null : context.bookId(), root);
        Platform.runLater(() -> {
            if (!current(context)) return;
            dialogService.showError(i18n.text("common.error"),
                    i18n.format("ui.reader.annotation.save_failed", rootMessage(root)));
            focus(context);
        });
        return null;
    }

    private static boolean usable(Context context) {
        return context != null && context.bookId() != null && !context.bookId().isBlank()
                && context.document() != null && context.document().text() != null
                && current(context);
    }

    private static boolean current(Context context) {
        return context != null && context.stillCurrent() != null && context.stillCurrent().getAsBoolean();
    }

    private static String quoteAndNote(ReaderAnnotationOverlay annotation) {
        if (annotation == null) return "";
        String quote = annotation.quote() == null ? "" : annotation.quote().trim();
        String note = annotation.noteText() == null ? "" : annotation.noteText().trim();
        if (note.isEmpty()) return quote;
        if (quote.isEmpty()) return note;
        return "“" + quote + "”\n\n" + note;
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? new IllegalStateException("Unknown annotation failure") : current;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = rootCause(error);
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    public record Context(
            Window owner,
            String bookId,
            String artifactId,
            ReaderDocument document,
            Set<String> knownTags,
            BooleanSupplier stillCurrent,
            Runnable clearSelection,
            Runnable refresh,
            Consumer<String> status,
            Runnable openManager,
            Runnable focusReader
    ) {
        public Context {
            knownTags = knownTags == null ? Set.of() : Set.copyOf(knownTags);
            clearSelection = clearSelection == null ? () -> { } : clearSelection;
            refresh = refresh == null ? () -> { } : refresh;
            status = status == null ? ignored -> { } : status;
            openManager = openManager == null ? () -> { } : openManager;
            focusReader = focusReader == null ? () -> { } : focusReader;
        }
    }
}
