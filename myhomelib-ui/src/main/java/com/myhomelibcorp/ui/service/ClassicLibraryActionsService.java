package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.book.EditBookUseCase;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.metadata.MetadataMergeUiService;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.service.LanguageResolver;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ClassicLibraryActionsService {
    private final BookQueryRepository query;
    private final BookMapper mapper;
    private final BookSaver bookSaver;
    private final ApplicationSettingsPort settings;
    private final DialogService dialogs;
    private final EditBookUseCase editBookUseCase;
    private final UiBackgroundExecutor backgroundExecutor;
    private final MetadataMergeUiService metadataMergeUi;
    private final LibraryOperationCoordinator operations;
    private final ApplicationState state;
    private final AtomicLong editGeneration = new AtomicLong();

    public ClassicLibraryActionsService(BookQueryRepository query, BookMapper mapper, BookSaver bookSaver,
                                        ApplicationSettingsPort settings, DialogService dialogs,
                                        EditBookUseCase editBookUseCase, UiBackgroundExecutor backgroundExecutor,
                                        MetadataMergeUiService metadataMergeUi,
                                        LibraryOperationCoordinator operations, ApplicationState state) {
        this.query = query;
        this.mapper = mapper;
        this.bookSaver = bookSaver;
        this.settings = settings;
        this.dialogs = dialogs;
        this.editBookUseCase = editBookUseCase;
        this.backgroundExecutor = backgroundExecutor;
        this.metadataMergeUi = metadataMergeUi;
        this.operations = operations;
        this.state = state;
    }

    public List<BookDto> newBooks(int limit) {
        var lease = operations.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            List<BookDto> result = query.findRecentlyAdded(limit).stream().map(mapper::toDto).toList();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    /**
     * Loads the dialog snapshot and performs the authoritative edit off the JavaFX thread.
     * onSuccess is invoked on the JavaFX thread only after the DB transaction has committed.
     */
    public void editBook(Window owner, BookId id, Runnable onSuccess) {
        if (id == null) return;
        LibraryOperationCoordinator.Lease lease;
        try {
            lease = operations.acquireDetachedShared(LibraryOperationType.EDIT);
        } catch (RuntimeException conflict) {
            dialogs.showError(UiText.text("ui.java.classic_library_actions_service.8d560155bf"), UiExceptionMessages.root(conflict));
            return;
        }
        var token = UiAsyncRequestGuard.next(editGeneration, state);
        backgroundExecutor.submit(() -> query.findById(id).orElse(null))
                .whenComplete((book, loadError) -> Platform.runLater(() -> {
                    if (!UiAsyncRequestGuard.isCurrent(token, editGeneration, state)) {
                        lease.close();
                        return;
                    }
                    if (loadError != null) {
                        lease.close();
                        dialogs.showError(UiText.text("ui.java.classic_library_actions_service.8d560155bf"), UiExceptionMessages.root(loadError));
                        return;
                    }
                    if (book == null) {
                        lease.close();
                        dialogs.showWarning(UiText.text("ui.java.classic_library_actions_service.457c748374"), UiText.text("ui.java.classic_library_actions_service.933f4f45c4"));
                        return;
                    }
                    try {
                        showEditDialog(owner, book, onSuccess, lease, token);
                    } catch (RuntimeException failure) {
                        lease.close();
                        dialogs.showError(UiText.text("ui.java.classic_library_actions_service.8d560155bf"), UiExceptionMessages.root(failure));
                    }
                }));
    }

    private void showEditDialog(Window owner, Book book, Runnable onSuccess,
                                LibraryOperationCoordinator.Lease lease, UiAsyncRequestToken token) {
        Dialog<ButtonType> d = new Dialog<>();
        DialogLocalization.prepare(d);
        d.setTitle(UiText.text("ui.java.classic_library_actions_service.1dbaaf6a09"));
        d.setHeaderText(book.getTitle());
        if (owner != null) d.initOwner(owner);
        ButtonType onlineMetadata = new ButtonType(UiText.text("ui.java.classic_library_actions_service.6cc06cadde"), ButtonBar.ButtonData.LEFT);
        d.getDialogPane().getButtonTypes().addAll(onlineMetadata, ButtonType.OK, ButtonType.CANCEL);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(7); g.setPadding(new Insets(12));
        TextField title = f(book.getTitle());
        TextField authors = f(book.authorsText());
        TextField series = f(book.getSeries());
        TextField seq = f(book.getSequenceNumber() == null ? "" : book.getSequenceNumber().toString());
        TextField lang = f(book.getLanguage() == null ? "" : book.getLanguage().toString());
        TextField year = f(book.getYear() == null ? "" : book.getYear().toString());
        TextField publisher = f(book.getPublisher());
        TextField keywords = f(book.getKeywords());
        TextArea annotation = ta(book.getAnnotation());
        TextArea review = ta(book.getReview());
        int r = 0;
        r = add(g, r, UiText.text("ui.bulkedit.field.title"), title); r = add(g, r, UiText.text("ui.java.classic_library_actions_service.d2dabe5730"), authors);
        r = add(g, r, UiText.text("ui.bulkedit.field.series"), series); r = add(g, r, UiText.text("ui.java.classic_library_actions_service.247f6f7bf4"), seq);
        r = add(g, r, UiText.text("ui.bulkedit.field.language"), lang); r = add(g, r, UiText.text("ui.bulkedit.field.year"), year);
        r = add(g, r, UiText.text("ui.smart_collection.field.publisher"), publisher); r = add(g, r, UiText.text("ui.fxml.details.keywords"), keywords);
        r = add(g, r, UiText.text("ui.bulkedit.field.annotation"), annotation); add(g, r, UiText.text("ui.java.classic_library_actions_service.ee742d1c37"), review);
        d.getDialogPane().setContent(g);
        d.setResizable(true);

        ButtonType result = d.showAndWait().orElse(ButtonType.CANCEL);
        if (result == onlineMetadata) {
            lease.close();
            metadataMergeUi.lookupAndReview(owner, book.getId(), onSuccess);
            return;
        }
        if (result != ButtonType.OK) { lease.close(); return; }
        if (title.getText() == null || title.getText().isBlank()) {
            lease.close();
            dialogs.showWarning(UiText.text("ui.java.classic_library_actions_service.73fa190726"), UiText.text("ui.java.classic_library_actions_service.286ea1d3e6"));
            return;
        }

        EditBookUseCase.Request request = new EditBookUseCase.Request(
                book.getId(),
                title.getText(),
                parseAuthors(authors.getText(), book.getAuthors()),
                series.getText(),
                intOrNull(seq.getText()),
                LanguageResolver.resolve(lang.getText()),
                intOrNull(year.getText()),
                publisher.getText(),
                keywords.getText(),
                annotation.getText(),
                review.getText());

        backgroundExecutor.submit(() -> editBookUseCase.execute(request))
                .whenComplete((updated, saveError) -> {
                    lease.close();
                    Platform.runLater(() -> {
                        if (!UiAsyncRequestGuard.isCurrent(token, editGeneration, state)) return;
                        if (saveError != null) {
                            dialogs.showError(UiText.text("ui.java.classic_library_actions_service.8d560155bf"), UiExceptionMessages.root(saveError));
                            return;
                        }
                        if (onSuccess != null) onSuccess.run();
                    });
                });
    }

    public boolean deleteBook(BookId id) {
        if (id == null) return false;
        var lease = operations.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
            String expectedCollectionId = currentCollectionId();
            Book book = query.findById(id).orElse(null);
            if (book == null) {
                lease.markCompleted("");
                return false;
            }
            if (settings.getBoolean("ui.confirmDelete", true)
                    && !dialogs.showConfirmation(UiText.text("ui.java.classic_library_actions_service.0422aa8a6f"), book.getTitle(),
                    UiText.text("ui.java.classic_library_actions_service.4d475e7478"))) {
                lease.markCancelled("");
                return false;
            }
            bookSaver.deleteBook(id, expectedCollectionId);
            lease.markCompleted("");
            return true;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private String currentCollectionId() {
        var collection = state.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }

    private List<Author> parseAuthors(String value, List<Author> fallback) {
        if (value == null || value.isBlank()) return fallback;
        List<Author> result = new ArrayList<>();
        for (String part : value.split(";")) {
            String p = part.trim();
            if (p.isBlank()) continue;
            String[] w = p.split("\\s+");
            if (w.length == 1) result.add(new Author("", "", w[0]));
            else if (w.length == 2) result.add(new Author(w[1], "", w[0]));
            else result.add(new Author(w[1], String.join(" ", java.util.Arrays.copyOfRange(w, 2, w.length)), w[0]));
        }
        return result.isEmpty() ? fallback : result;
    }

    private static TextField f(String s) { return new TextField(s == null ? "" : s); }
    private static TextArea ta(String s) {
        TextArea a = new TextArea(s == null ? "" : s); a.setPrefRowCount(4); a.setWrapText(true); return a;
    }
    private static int add(GridPane g, int row, String label, javafx.scene.Node n) {
        Label l = new Label(label); g.add(l, 0, row); g.add(n, 1, row); GridPane.setHgrow(n, Priority.ALWAYS); return row + 1;
    }
    private static Integer intOrNull(String s) {
        try { return s == null || s.isBlank() ? null : Integer.valueOf(s.trim()); }
        catch (Exception e) { return null; }
    }
}
