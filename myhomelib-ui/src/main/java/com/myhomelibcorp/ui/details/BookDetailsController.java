package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.ui.service.DialogLocalization;

import com.myhomelibcorp.shared.util.FileNameSupport;
import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.ui.service.BookDisplayText;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.book.SelectPreferredBookArtifactUseCase;
import com.myhomelibcorp.application.recommendation.FindSimilarBooksUseCase;
import com.myhomelibcorp.application.recommendation.SimilarBookResult;
import com.myhomelibcorp.application.comic.duplicates.ScanComicDuplicatePagesUseCase;
import com.myhomelibcorp.application.comic.duplicates.ComicDuplicateScanControl;
import com.myhomelibcorp.application.comic.duplicates.ComicDuplicateScanResult;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.reader.inspection.DocumentImageInfo;
import com.myhomelibcorp.reader.inspection.DocumentInspection;
import com.myhomelibcorp.reader.inspection.TocPreviewEntry;
import com.myhomelibcorp.ui.details.model.RichBookDetailsSession;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.ClassicLibraryActionsService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.ArtifactStateText;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookDetailsViewModel;
import jakarta.annotation.PreDestroy;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Pagination;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookDetailsController {

    private static final int MAX_GALLERY_IMAGE_BYTES = 24 * 1024 * 1024;

    private final ApplicationState appState;
    private final NavigationService navigationService;
    private final ClassicLibraryActionsService classicLibraryActionsService;
    private final CoverPresenter coverPresenter;
    private final LocalizationService localizationService;
    private final BookDetailsAnalysisService analysisService;
    private final UiBackgroundExecutor backgroundExecutor;
    private final SelectPreferredBookArtifactUseCase selectPreferredBookArtifactUseCase;
    private final FindSimilarBooksUseCase findSimilarBooksUseCase;
    private final ScanComicDuplicatePagesUseCase scanComicDuplicatePagesUseCase;
    private final BookDownloadCoordinator bookDownloadCoordinator;

    @FXML private ImageView coverImageView;
    @FXML private Label titleLabel;
    @FXML private FlowPane authorsLinksPane;
    @FXML private FlowPane seriesLinksPane;
    @FXML private FlowPane genresLinksPane;
    @FXML private Label languageLabel;
    @FXML private Label sourceLanguageLabel;
    @FXML private Label yearLabel;
    @FXML private Hyperlink publisherLink;
    @FXML private Label isbnLabel;
    @FXML private Label translatorsLabel;
    @FXML private Label formatLabel;
    @FXML private Label fileSizeLabel;
    @FXML private FlowPane artifactBadgesPane;
    @FXML private ComboBox<BookArtifactDto> artifactComboBox;
    @FXML private Label artifactLocationLabel;
    @FXML private Button setPreferredArtifactButton;
    @FXML private Button openArtifactButton;
    @FXML private Label textStatsLabel;
    @FXML private Label localStatusLabel;
    @FXML private Label ratingLabel;
    @FXML private Label progressLabel;
    @FXML private Label libraryRateLabel;
    @FXML private Label sourceUrlLabel;
    @FXML private Label documentWarningLabel;
    @FXML private Button downloadBookButton;
    @FXML private FlowPane keywordsLinksPane;
    @FXML private FlowPane groupLinksPane;
    @FXML private FlowPane reviewLinksPane;
    @FXML private VBox tocPreviewBox;
    @FXML private VBox similarBooksBox;
    @FXML private Label imagesCountLabel;
    @FXML private Button showImagesButton;
    @FXML private Button scanDuplicatePagesButton;
    @FXML private Label annotationLabel;
    @FXML private TextArea reviewArea;

    private final AtomicLong loadGeneration = new AtomicLong();
    private ChangeListener<BookDto> bookChangeListener;
    private javafx.beans.value.ChangeListener<Number> readingProgressListener;
    private RichBookDetailsSession currentSession;
    private volatile UiBackgroundExecutor.CancellableSubmission<RichBookDetailsSession> detailsAnalysisTask;
    private volatile UiBackgroundExecutor.CancellableSubmission<List<SimilarBookResult>> similarBooksTask;

    @FXML
    public void initialize() {
        coverPresenter.bind(coverImageView);
        BookDetailsViewModel vm = appState.getBookDetails();
        bookChangeListener = (obs, old, bookDto) -> loadBookDetails(bookDto);
        vm.currentBookProperty().addListener(bookChangeListener);
        readingProgressListener = (obs, oldValue, newValue) -> {
            BookDto selected = vm.getCurrentBook();
            if (selected != null && progressLabel != null) {
                progressLabel.setText(UiText.text("ui.java.book_details_controller.807433eeb3")
                        + newValue.intValue() + "%");
            }
        };
        vm.readingProgressProperty().addListener(readingProgressListener);
        configureArtifactPicker();
        loadBookDetails(vm.getCurrentBook());
    }

    private void loadBookDetails(BookDto selected) {
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(loadGeneration, appState);
        cancelDetailsAnalysis();
        cancelSimilarBooks();
        closeCurrentSession();
        coverPresenter.clearCover();

        if (selected == null || selected.getId() == null || selected.getId().isBlank()) {
            clearUI();
            return;
        }
        log.info("USER_ACTION_BOOK_DETAILS_SELECT id={} title='{}' local={} preferredArtifact={} artifacts={}",
                selected.getId(), selected.getTitle() == null ? "" : selected.getTitle().replace('\r', ' ').replace('\n', ' ').trim(),
                selected.isLocal(), selected.getPreferredArtifactId(), selected.getArtifacts().size());

        // Catalogue/storage metadata may be stale after an earlier interrupted download or an
        // external copy. Probe the active collection root before declaring the book remote; this
        // must never trigger HTTP.
        bookDownloadCoordinator.reconcileLocalClaim(selected);
        showLoading(selected);
        if (!hasLocalArtifactClaim(selected)) {
            // A remote-only catalogue record is a normal state, not an inspection failure.
            // Do not invoke parsers/materialization until a physical local representation exists.
            showWarning(UiText.text("ui.book_details.not_downloaded"));
            updateDownloadAction(selected);
            loadSimilarBooks(selected.getId(), requestToken);
            return;
        }
        updateDownloadAction(selected);
        // Start the cover immediately from the selected local DTO. It must not wait for the richer
        // text analysis to finish; rapid navigation cancels the old cover request independently.
        coverPresenter.showCover(selected);
        // Passive selection enrichment must not own the global library-operation barrier.
        // The request token below discards results after selection/collection changes, while
        // ordinary foreground operations remain available to the user during file inspection.
        UiBackgroundExecutor.CancellableSubmission<RichBookDetailsSession> submission =
                backgroundExecutor.submitInteractiveCancellable(() -> analysisService.analyze(selected.getId()));
        detailsAnalysisTask = submission;
        submission.completion()
                .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(session -> UiExecutor.runOnUiThread(() -> {
                    BookDto stillSelected = appState.getBookDetails().getCurrentBook();
                    boolean stale = !UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)
                            || stillSelected == null
                            || !selected.getId().equals(stillSelected.getId());
                    if (stale) {
                        session.close();
                        return;
                    }
                    closeCurrentSession();
                    currentSession = session;
                    updateUI(session);
                    // Rich inspection re-reads metadata; preserve the visible catalogue progress.
                    progressLabel.setText(UiText.text("ui.java.book_details_controller.807433eeb3")
                            + BookProgressPresentation.preserveVisibleProgress(session.book(), stillSelected).getProgressFormatted());
                    coverPresenter.showCover(session.book());
                    loadSimilarBooks(selected.getId(), requestToken);
                }))
                .exceptionally(ex -> {
                    Throwable cause = unwrapCompletion(ex);
                    if (cause instanceof java.util.concurrent.CancellationException
                            || cause instanceof InterruptedException) {
                        log.debug("Скасовано застарілий аналіз відомостей для {}", selected.getId());
                        return null;
                    }
                    if (cause instanceof java.util.concurrent.TimeoutException) {
                        submission.cancel();
                        log.warn("BOOK_DETAILS_TIMEOUT title='{}' id={} timeoutMs=5000",
                                selected.getTitle() == null ? "" : selected.getTitle().replace('\r', ' ').replace('\n', ' ').trim(),
                                selected.getId());
                    } else {
                        log.warn("Не вдалося завантажити rich details для {}: {}", selected.getId(), cause.getMessage());
                    }
                    UiExecutor.runOnUiThread(() -> {
                        BookDto stillSelected = appState.getBookDetails().getCurrentBook();
                        if (UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)
                                && stillSelected != null && selected.getId().equals(stillSelected.getId())) {
                            updateBasicUI(selected);
                            showWarning(UiText.text("ui.java.book_details_controller.bbd9f92b75"));
                        }
                    });
                    return null;
                })
                .whenComplete((ignored, ignoredFailure) -> {
                    if (detailsAnalysisTask == submission) detailsAnalysisTask = null;
                });
    }

    private void loadSimilarBooks(String bookId, UiAsyncRequestToken requestToken) {
        if (similarBooksBox == null) return;
        similarBooksBox.getChildren().setAll(new ProgressIndicator());
        // Similar-books lookup is derived, read-only presentation work. Do not advertise it as a
        // library operation or block unrelated user commands while the details pane is enriching.
        cancelSimilarBooks();
        UiBackgroundExecutor.CancellableSubmission<List<SimilarBookResult>> submission =
                backgroundExecutor.submitInteractiveCancellable(() -> findSimilarBooksUseCase.find(BookId.fromString(bookId), 8));
        similarBooksTask = submission;
        submission.completion().orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(items -> UiExecutor.runOnUiThread(() -> {
                    BookDto current = appState.getBookDetails().getCurrentBook();
                    if (!UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)
                            || current == null || !bookId.equals(current.getId())) return;
                    renderSimilarBooks(items);
                }))
                .exceptionally(ex -> {
                    Throwable cause = unwrapCompletion(ex);
                    if (cause instanceof java.util.concurrent.TimeoutException) submission.cancel();
                    if (!(cause instanceof java.util.concurrent.CancellationException)) {
                        log.debug("Similar books unavailable for {}: {}", bookId, cause.getMessage());
                    }
                    UiExecutor.runOnUiThread(() -> {
                        BookDto current = appState.getBookDetails().getCurrentBook();
                        if (UiAsyncRequestGuard.isCurrent(requestToken, loadGeneration, appState)
                                && current != null && bookId.equals(current.getId()) && similarBooksBox != null) {
                            similarBooksBox.getChildren().setAll(new Label("—"));
                        }
                    });
                    return null;
                }).whenComplete((ignored, failure) -> {
                    if (similarBooksTask == submission) similarBooksTask = null;
                });
    }

    private void renderSimilarBooks(List<SimilarBookResult> items) {
        similarBooksBox.getChildren().clear();
        if (items == null || items.isEmpty()) { similarBooksBox.getChildren().add(new Label("—")); return; }
        for (SimilarBookResult item : items) {
            Hyperlink link = new Hyperlink(item.title());
            link.setId("similar-book-open-" + item.bookId());
            String why = item.reasons().stream().limit(3).map(r -> r.detail()).filter(v -> v != null && !v.isBlank())
                    .distinct().collect(java.util.stream.Collectors.joining(" · "));
            if (!why.isBlank()) link.setTooltip(new Tooltip(why));
            link.setOnAction(e -> navigationService.navigateToBook(BookId.fromString(item.bookId())));
            similarBooksBox.getChildren().add(link);
        }
    }

    private void updateUI(RichBookDetailsSession session) {
        BookDto book = session.book();
        DocumentInspection document = session.inspection();

        titleLabel.setText(value(book.getTitle(), UiText.text("ui.java.book_details_controller.6424359615")));
        renderAuthorLinks(book.getAuthors(), book.getAuthorsText());
        renderSeriesLink(book.getSeries());
        renderGenreLinks(book.getGenreItems(), book.getGenresText());

        languageLabel.setText(UiText.text("ui.java.book_details_controller.05fc3de293") + value(firstNonBlank(book.getLanguage(), document.language()), "—"));
        sourceLanguageLabel.setText(UiText.text("ui.java.book_details_controller.96a6cff96e") + value(document.sourceLanguage(), "—"));
        yearLabel.setText(UiText.text("ui.java.book_details_controller.71400a276c") + year(book, document));

        String publisher = firstNonBlank(book.getPublisher(), document.publisher());
        configurePublisherLink(publisher);

        isbnLabel.setText(UiText.text("ui.common.isbn_prefix") + value(firstNonBlank(book.getIsbn(), document.isbn()), "—"));
        translatorsLabel.setText(UiText.text("ui.java.book_details_controller.1c85c0f644") + value(book.getTranslators(), "—"));
        formatLabel.setText(UiText.text("ui.java.book_details_controller.e7c843a4f1") + value(document.format(), detectFormat(book)));
        configureDuplicatePageScanAction(book, document);
        fileSizeLabel.setText(UiText.text("ui.java.book_details_controller.cc8ba91da2") + value(BookDisplayText.fileSize(book.getFileSize()), "—"));
        renderArtifacts(book);
        textStatsLabel.setText(formatTextStats(document));
        localStatusLabel.setText(UiText.text("ui.java.book_details_controller.156979c7cc") + BookDisplayText.localStatus(book));
        ratingLabel.setText(UiText.text("ui.java.book_details_controller.d9a3dd1c0a") + value(book.getRateStars(), "—"));
        progressLabel.setText(UiText.text("ui.java.book_details_controller.807433eeb3") + book.getProgressFormatted());
        libraryRateLabel.setText(UiText.text("ui.java.book_details_controller.e3f76633e7") + (book.getLibraryRate() > 0 ? book.getLibraryRate() : "—"));
        sourceUrlLabel.setText(UiText.text("ui.java.book_details_controller.90ba2d4947") + value(book.getSourceUrl(), "—"));

        String annotation = firstNonBlank(book.getAnnotation(), document.annotation());
        annotationLabel.setText(annotation);
        reviewArea.setText(value(book.getReview(), ""));

        renderKeywordLinks(book.getKeywords());
        renderGroupLinks(session.groups());
        renderReviewLinks(book);
        renderToc(document.tocPreview());
        renderImages(document.images());
        showInspectionWarning(document.warning());
    }

    private void updateBasicUI(BookDto book) {
        titleLabel.setText(value(book.getTitle(), UiText.text("ui.java.book_details_controller.6424359615")));
        renderAuthorLinks(book.getAuthors(), book.getAuthorsText());
        renderSeriesLink(book.getSeries());
        renderGenreLinks(book.getGenreItems(), book.getGenresText());
        languageLabel.setText(UiText.text("ui.java.book_details_controller.05fc3de293") + value(book.getLanguage(), "—"));
        sourceLanguageLabel.setText(UiText.text("ui.fxml.details.original_language"));
        yearLabel.setText(UiText.text("ui.java.book_details_controller.71400a276c") + (book.getYear() != null && book.getYear() > 0 ? book.getYear() : "—"));
        configurePublisherLink(book.getPublisher());
        isbnLabel.setText(UiText.text("ui.common.isbn_prefix") + value(book.getIsbn(), "—"));
        translatorsLabel.setText(UiText.text("ui.java.book_details_controller.1c85c0f644") + value(book.getTranslators(), "—"));
        annotationLabel.setText(value(book.getAnnotation(), ""));
        reviewArea.setText(value(book.getReview(), ""));
        formatLabel.setText(UiText.text("ui.java.book_details_controller.e7c843a4f1") + detectFormat(book));
        fileSizeLabel.setText(UiText.text("ui.java.book_details_controller.cc8ba91da2") + value(BookDisplayText.fileSize(book.getFileSize()), "—"));
        renderArtifacts(book);
        localStatusLabel.setText(UiText.text("ui.java.book_details_controller.156979c7cc") + BookDisplayText.localStatus(book));
        ratingLabel.setText(UiText.text("ui.java.book_details_controller.d9a3dd1c0a") + value(book.getRateStars(), "—"));
        progressLabel.setText(UiText.text("ui.java.book_details_controller.807433eeb3") + book.getProgressFormatted());
        libraryRateLabel.setText(UiText.text("ui.java.book_details_controller.e3f76633e7") + (book.getLibraryRate() > 0 ? book.getLibraryRate() : "—"));
        sourceUrlLabel.setText(UiText.text("ui.java.book_details_controller.90ba2d4947") + value(book.getSourceUrl(), "—"));
        renderKeywordLinks(book.getKeywords());
        tocPreviewBox.getChildren().setAll(new Label("—"));
        if (similarBooksBox != null) similarBooksBox.getChildren().setAll(new Label("—"));
        renderImages(List.of());
    }

    private void showLoading(BookDto book) {
        // The table/search DTO already contains useful catalogue metadata. Render it immediately
        // instead of blanking the panel until document inspection has parsed/materialized the file.
        updateBasicUI(book);
        documentWarningLabel.setText(UiText.text("ui.java.book_details_controller.cffb3bd684"));
        documentWarningLabel.setVisible(true);
        documentWarningLabel.setManaged(true);
    }

    private static boolean hasLocalArtifactClaim(BookDto book) {
        if (book == null) return false;
        // A local artifact in MISSING/CORRUPT state is still a local artifact claim and must
        // reach inspection so the UI can show the technical local-file problem. Only records
        // with no local representation at all are treated as the normal remote-only state.
        if (book.isLocal()) return true;
        return book.getArtifacts().stream().anyMatch(artifact -> artifact != null && artifact.isLocal());
    }

    private void updateDownloadAction(BookDto book) {
        if (downloadBookButton == null) return;
        boolean visible = !hasLocalArtifactClaim(book) && bookDownloadCoordinator.canDownload(book);
        downloadBookButton.setVisible(visible);
        downloadBookButton.setManaged(visible);
        downloadBookButton.setDisable(!visible || bookDownloadCoordinator.isDownloading(book));
    }

    @FXML
    private void onDownloadBook() {
        BookDto book = currentBook();
        if (book == null || hasLocalArtifactClaim(book) || !bookDownloadCoordinator.canDownload(book)) return;
        downloadBookButton.setDisable(true);
        String expectedBookId = book.getId();
        bookDownloadCoordinator.ensureLocal(book).whenComplete((path, error) -> UiExecutor.runOnUiThread(() -> {
            BookDto selected = currentBook();
            if (selected == null || !java.util.Objects.equals(expectedBookId, selected.getId())) return;
            if (error != null) {
                updateDownloadAction(selected);
                return;
            }
            // ensureLocal refreshes storage state. Re-run details so the newly local file is
            // inspected immediately without requiring a second user download/click.
            loadBookDetails(selected);
        }));
    }

    private void configureDuplicatePageScanAction(BookDto book, DocumentInspection document) {
        if (scanDuplicatePagesButton == null) return;
        String format = firstNonBlank(document == null ? "" : document.format(), detectFormat(book));
        boolean comic = format != null && switch (format.trim().toLowerCase(Locale.ROOT)) {
            case "cbz", "cbr", "cb7" -> true;
            default -> false;
        };
        scanDuplicatePagesButton.setVisible(comic);
        scanDuplicatePagesButton.setManaged(comic);
        scanDuplicatePagesButton.setDisable(!comic);
    }

    @FXML
    private void onScanDuplicatePages() {
        BookDto current = appState.getBookDetails().getCurrentBook();
        if (current == null || current.getId() == null || current.getId().isBlank()) return;
        scanDuplicatePagesButton.setDisable(true);
        String bookId = current.getId();
        backgroundExecutor.submitCollectionScoped(LibraryOperationType.QUERY,
                        () -> scanComicDuplicatePagesUseCase.scan(BookId.fromString(bookId), ComicDuplicateScanControl.none()))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    BookDto selected = appState.getBookDetails().getCurrentBook();
                    if (selected != null && bookId.equals(selected.getId())) scanDuplicatePagesButton.setDisable(false);
                    showDuplicatePagesReport(result);
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        scanDuplicatePagesButton.setDisable(false);
                        Dialog<ButtonType> dialog = new Dialog<>();
                        DialogLocalization.prepare(dialog);
                        dialog.setTitle(UiText.text("ui.comic.duplicates.title"));
                        dialog.setHeaderText(UiText.text("ui.comic.duplicates.failed"));
                        dialog.setContentText(com.myhomelibcorp.ui.util.UiExceptionSupport.message(ex));
                        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                        dialog.showAndWait();
                    });
                    return null;
                });
    }

    private void showDuplicatePagesReport(ComicDuplicateScanResult result) {
        StringBuilder text = new StringBuilder();
        if (result.groups().isEmpty()) text.append(UiText.text("ui.comic.duplicates.none"));
        else {
            for (int i=0;i<result.groups().size();i++) {
                var group=result.groups().get(i);
                text.append(UiText.text("ui.comic.duplicates.group")).append(' ').append(i+1)
                        .append(" [").append(UiText.text("ui.comic.duplicates.kind_exact")).append("]: ")
                        .append(group.pages().stream().map(p -> (p.pageIndex()+1) + " — " + p.entryName())
                                .collect(java.util.stream.Collectors.joining("; "))).append('\n');
            }
        }
        if (!result.failures().isEmpty()) {
            text.append('\n').append(UiText.text("ui.comic.duplicates.failures")).append('\n');
            result.failures().forEach(f -> text.append(f.pageIndex()+1).append(" — ").append(f.entryName()).append(": ").append(f.reason()).append('\n'));
        }
        TextArea area = new TextArea(text.toString()); area.setEditable(false); area.setWrapText(true); area.setPrefSize(760,420);
        Dialog<ButtonType> dialog = new Dialog<>(); DialogLocalization.prepare(dialog);
        dialog.setTitle(UiText.text("ui.comic.duplicates.title"));
        dialog.setHeaderText(UiText.text("ui.comic.duplicates.summary") + " " + result.scannedPages() + "/" + result.totalPages());
        dialog.getDialogPane().setContent(area); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.showAndWait();
    }

    private void configureArtifactPicker() {
        artifactComboBox.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(BookArtifactDto artifact, boolean empty) {
                super.updateItem(artifact, empty);
                setText(empty || artifact == null ? null : artifactChoiceText(artifact));
                setDisable(!empty && artifact != null && !isArtifactOpenable(artifact));
            }
        });
        artifactComboBox.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(BookArtifactDto artifact, boolean empty) {
                super.updateItem(artifact, empty);
                setText(empty || artifact == null ? null : artifactChoiceText(artifact));
            }
        });
        artifactComboBox.valueProperty().addListener((obs, old, selected) -> updateArtifactActions(selected));
    }

    private void renderArtifacts(BookDto book) {
        List<BookArtifactDto> artifacts = book == null ? List.of() : book.getArtifacts();
        artifactBadgesPane.getChildren().clear();
        artifactComboBox.getItems().setAll(artifacts);
        if (artifacts.isEmpty()) {
            artifactBadgesPane.getChildren().add(new Label("—"));
            artifactComboBox.setDisable(true);
            artifactLocationLabel.setText(UiText.text("ui.fxml.details.source_location"));
            updateArtifactActions(null);
            return;
        }
        artifactComboBox.setDisable(false);
        for (BookArtifactDto artifact : artifacts) {
            Label badge = new Label(artifactBadgeText(book, artifact));
            badge.getStyleClass().add("artifact-badge");
            if (artifact.getId() != null && artifact.getId().equals(book.getPreferredArtifactId())) {
                badge.getStyleClass().add("artifact-badge-preferred");
            }
            if (!isArtifactOpenable(artifact)) badge.getStyleClass().add("artifact-badge-unavailable");
            artifactBadgesPane.getChildren().add(badge);
        }
        BookArtifactDto preferred = book.getPreferredArtifact();
        artifactComboBox.getSelectionModel().select(preferred != null ? preferred : artifacts.getFirst());
        updateArtifactActions(artifactComboBox.getValue());
    }

    private void updateArtifactActions(BookArtifactDto artifact) {
        BookDto book = currentBook();
        boolean valid = artifact != null && book != null;
        boolean preferred = valid && artifact.getId() != null && artifact.getId().equals(book.getPreferredArtifactId());
        setPreferredArtifactButton.setDisable(!valid || preferred);
        openArtifactButton.setDisable(!valid || !isArtifactOpenable(artifact));
        artifactLocationLabel.setText(valid ? UiText.text("ui.java.book_details_controller.71adf0102e") + artifactLocation(artifact) : UiText.text("ui.fxml.details.source_location"));
    }

    private static boolean isArtifactOpenable(BookArtifactDto artifact) {
        return artifact != null && artifact.isLocal() && "AVAILABLE".equalsIgnoreCase(artifact.getState());
    }

    private String artifactBadgeText(BookDto book, BookArtifactDto artifact) {
        String rawFormat = value(artifact.getFormat(), "");
        String format = rawFormat.isBlank() ? UiText.text("common.file") : rawFormat.toUpperCase(Locale.ROOT);
        String preferred = artifact.getId() != null && artifact.getId().equals(book.getPreferredArtifactId()) ? " ★" : "";
        String unavailable = isArtifactOpenable(artifact) ? "" : " · " + localizedArtifactState(artifact);
        return format + preferred + unavailable;
    }

    private String artifactChoiceText(BookArtifactDto artifact) {
        if (artifact == null) return "—";
        String suffix = isArtifactOpenable(artifact) ? "" : " [" + localizedArtifactState(artifact) + "]";
        return artifact.getDisplayName() + suffix;
    }


    private String localizedArtifactState(BookArtifactDto artifact) {
        return localizationService.tr(ArtifactStateText.sourceLabel(
                artifact == null ? null : artifact.getState(),
                artifact != null && artifact.isLocal()));
    }

    private static String artifactLocation(BookArtifactDto artifact) {
        String source = value(artifact.getSourceId(), UiText.text("ui.java.book_details_controller.b47aa52233"));
        String entry = FileNameSupport.decodeHashUnicodeEscapes(
                firstNonBlank(artifact.getArchiveEntry(), artifact.getFileName()));
        String base = firstNonBlank(artifact.getCollectionRoot(), artifact.getFolder());
        String location = base.isBlank() ? entry : (entry.isBlank() ? base : base + "/" + entry);
        return source + " · " + value(location, "—");
    }

    @FXML
    private void onSetPreferredArtifact() {
        BookDto book = currentBook();
        BookArtifactDto artifact = artifactComboBox.getValue();
        if (book == null || artifact == null || artifact.getId() == null || artifact.getId().isBlank()) return;
        String expectedCollectionId = appState.getCurrentLibraryCollectionId();
        setPreferredArtifactButton.setDisable(true);
        backgroundExecutor.submitCollectionScoped(LibraryOperationType.EDIT,
                        () -> selectPreferredBookArtifactUseCase.execute(BookId.fromString(book.getId()), artifact.getId()))
                .thenAccept(refreshed -> UiExecutor.runOnUiThread(() -> {
                    BookDto stillSelected = currentBook();
                    if (java.util.Objects.equals(expectedCollectionId, appState.getCurrentLibraryCollectionId())
                            && stillSelected != null
                            && java.util.Objects.equals(book.getId(), stillSelected.getId())) {
                        appState.getBookDetails().setCurrentBook(refreshed);
                    }
                }))
                .exceptionally(ex -> {
                    log.warn("Не вдалося змінити preferred artifact {}: {}", artifact.getId(), ex.getMessage());
                    UiExecutor.runOnUiThread(() -> {
                        BookDto stillSelected = currentBook();
                        if (java.util.Objects.equals(expectedCollectionId, appState.getCurrentLibraryCollectionId())
                                && stillSelected != null
                                && java.util.Objects.equals(book.getId(), stillSelected.getId())) {
                            setPreferredArtifactButton.setDisable(false);
                        }
                    });
                    return null;
                });
    }

    @FXML
    private void onOpenSelectedArtifact() {
        BookDto book = currentBook();
        BookArtifactDto artifact = artifactComboBox.getValue();
        if (book != null && isArtifactOpenable(artifact)) navigationService.openBookArtifact(book, artifact);
    }

    private void configurePublisherLink(String publisher) {
        String actual = publisher == null ? "" : publisher.trim();
        publisherLink.setText(UiText.text("ui.java.book_details_controller.1ee47fe5c7") + value(actual, "—"));
        boolean available = !actual.isBlank();
        publisherLink.setDisable(!available);
        publisherLink.setOnAction(available ? event -> navigationService.navigateToPublisher(actual) : null);
    }

    private void renderAuthorLinks(List<AuthorDto> authors, String fallback) {
        authorsLinksPane.getChildren().clear();
        List<AuthorDto> safeAuthors = authors != null ? authors : Collections.emptyList();
        if (!safeAuthors.isEmpty()) {
            for (AuthorDto author : safeAuthors) {
                if (author == null || author.getId() == null || author.getId().isBlank()) continue;
                Hyperlink link = new Hyperlink(value(author.getFullName(), author.getShortName()));
                link.setOnAction(event -> navigationService.navigateToAuthor(AuthorId.fromString(author.getId())));
                authorsLinksPane.getChildren().add(link);
            }
        }
        if (authorsLinksPane.getChildren().isEmpty()) {
            authorsLinksPane.getChildren().add(new Label(value(fallback, UiText.text("common.author.unknown"))));
        }
    }

    private void renderSeriesLink(String series) {
        seriesLinksPane.getChildren().clear();
        if (series == null || series.isBlank()) {
            seriesLinksPane.getChildren().add(new Label("—"));
            return;
        }
        Hyperlink link = new Hyperlink(series);
        link.setOnAction(event -> navigationService.navigateToSeriesByName(series));
        seriesLinksPane.getChildren().add(link);
    }

    private void renderGenreLinks(List<GenreDto> genres, String fallback) {
        genresLinksPane.getChildren().clear();
        List<GenreDto> safeGenres = genres != null ? genres : Collections.emptyList();
        List<String> siblingCodes = safeGenres.stream()
                .filter(java.util.Objects::nonNull)
                .map(GenreDto::getCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        if (!safeGenres.isEmpty()) {
            for (GenreDto genre : safeGenres) {
                if (genre == null || genre.getCode() == null || genre.getCode().isBlank()) continue;
                if (!localizationService.shouldDisplayGenre(genre.getCode(), siblingCodes)) continue;
                String label = localizationService.genreName(genre.getCode(), genre.getName());
                if (label == null || label.isBlank()) continue;
                Hyperlink link = new Hyperlink(label);
                link.setOnAction(event -> navigationService.navigateToGenre(GenreId.fromCode(genre.getCode())));
                genresLinksPane.getChildren().add(link);
            }
        }
        // Raw fallback text may contain internal genre codes or top-level groups; never expose them.
        if (genresLinksPane.getChildren().isEmpty()) genresLinksPane.getChildren().add(new Label("—"));
    }

    private void renderKeywordLinks(String rawKeywords) {
        keywordsLinksPane.getChildren().clear();
        List<String> keywords = splitKeywords(rawKeywords);
        if (keywords.isEmpty()) {
            keywordsLinksPane.getChildren().add(new Label("—"));
            return;
        }
        for (String keyword : keywords) {
            Hyperlink link = new Hyperlink(keyword);
            link.setOnAction(event -> navigationService.navigateToKeyword(keyword));
            keywordsLinksPane.getChildren().add(link);
        }
    }

    private void renderGroupLinks(List<GroupDto> groups) {
        groupLinksPane.getChildren().clear();
        List<GroupDto> safeGroups = groups != null ? groups : Collections.emptyList();
        if (!safeGroups.isEmpty()) {
            for (GroupDto group : safeGroups) {
                if (group == null || group.getId() == null) continue;
                Hyperlink link = new Hyperlink(groupDisplayName(group.getName()));
                link.setOnAction(event -> navigationService.navigateToGroup(GroupId.fromLong(group.getId())));
                groupLinksPane.getChildren().add(link);
            }
        }
        if (groupLinksPane.getChildren().isEmpty()) groupLinksPane.getChildren().add(new Label("—"));
    }

    private void renderReviewLinks(BookDto book) {
        reviewLinksPane.getChildren().clear();
        boolean rated = book.getRate() > 0;
        boolean reviewed = book.getReview() != null && !book.getReview().isBlank();
        if (rated) addReviewLink(UiText.text("ui.java.book_details_controller.d5ccd0eb01"), ReviewNavigationFilter.RATED);
        if (reviewed) addReviewLink(UiText.text("ui.java.book_details_controller.5228db4abf"), ReviewNavigationFilter.REVIEWED);
        if (rated && reviewed) addReviewLink(UiText.text("ui.java.book_details_controller.d03ee34079"), ReviewNavigationFilter.RATED_AND_REVIEWED);
        if (reviewLinksPane.getChildren().isEmpty()) reviewLinksPane.getChildren().add(new Label("—"));
    }

    private void addReviewLink(String title, ReviewNavigationFilter filter) {
        Hyperlink link = new Hyperlink(localizationService.tr(title));
        link.setOnAction(event -> navigationService.navigateToReviews(filter));
        reviewLinksPane.getChildren().add(link);
    }

    private void renderToc(List<TocPreviewEntry> toc) {
        tocPreviewBox.getChildren().clear();
        if (toc == null || toc.isEmpty()) {
            tocPreviewBox.getChildren().add(new Label("—"));
            return;
        }
        for (TocPreviewEntry entry : toc) {
            Label label = new Label(entry.title());
            label.setWrapText(true);
            label.setStyle("-fx-padding: 0 0 0 " + Math.min(36, entry.level() * 12) + ";");
            tocPreviewBox.getChildren().add(label);
        }
    }

    private void renderImages(List<DocumentImageInfo> images) {
        int count = images == null ? 0 : images.size();
        imagesCountLabel.setText(UiText.text("ui.java.book_details_controller.cd8f2113f9") + count);
        showImagesButton.setDisable(count == 0 || currentSession == null);
        showImagesButton.setVisible(count > 0);
        showImagesButton.setManaged(count > 0);
    }


    private void showInspectionWarning(String technicalWarning) {
        if (technicalWarning == null || technicalWarning.isBlank()) {
            showWarning("");
            return;
        }
        log.warn("Book inspection technical warning: {}", technicalWarning);
        showWarning(UiText.text("ui.book_details.inspection_warning"));
    }

    private void showWarning(String warning) {
        boolean visible = warning != null && !warning.isBlank();
        documentWarningLabel.setText(visible ? warning : "");
        documentWarningLabel.setVisible(visible);
        documentWarningLabel.setManaged(visible);
    }

    @FXML
    private void onShowImages() {
        RichBookDetailsSession session = currentSession;
        if (session == null || session.inspection().images().isEmpty()) return;
        List<DocumentImageInfo> images = session.inspection().images();
        Dialog<Void> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.setTitle(UiText.text("ui.java.book_details_controller.20fb4dd69a") + session.book().getTitle());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);

        Window owner = coverImageView != null && coverImageView.getScene() != null
                ? coverImageView.getScene().getWindow() : null;
        if (owner != null) dialog.initOwner(owner);

        Pagination pagination = new Pagination(images.size(), 0);
        pagination.setPageFactory(index -> createImagePage(session, images.get(index)));
        dialog.getDialogPane().setContent(pagination);

        Rectangle2D bounds = owner == null
                ? Screen.getPrimary().getVisualBounds()
                : Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight())
                        .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        double width = Math.min(900.0, Math.max(1.0, bounds.getWidth() * 0.90));
        double height = Math.min(720.0, Math.max(1.0, bounds.getHeight() * 0.85));
        dialog.getDialogPane().setPrefSize(width, height);
        dialog.show();
    }

    private Node createImagePage(RichBookDetailsSession session, DocumentImageInfo info) {
        StackPane pane = new StackPane();
        pane.setAlignment(Pos.CENTER);
        pane.setMinSize(0, 0);
        ProgressIndicator progress = new ProgressIndicator();
        pane.getChildren().add(progress);

        if (info.length() > MAX_GALLERY_IMAGE_BYTES) {
            pane.getChildren().setAll(new Label(UiText.text("ui.java.book_details_controller.926a7b386a")));
            return pane;
        }

        backgroundExecutor.submit(() -> readImage(session, info.id()))
                .thenAccept(bytes -> UiExecutor.runOnUiThread(() -> {
                    if (bytes == null || bytes.length == 0) {
                        pane.getChildren().setAll(new Label(UiText.text("ui.java.book_details_controller.fe9d1629d9")));
                        return;
                    }
                    // Decode to a bounded backing image, then bind the view to the actual dialog viewport.
                    Image image = new Image(new ByteArrayInputStream(bytes), 1600, 1200, true, true);
                    if (image.isError()) {
                        pane.getChildren().setAll(new Label(UiText.text("ui.java.book_details_controller.6389664a84")));
                    } else {
                        ImageView view = new ImageView(image);
                        view.setPreserveRatio(true);
                        view.setSmooth(true);
                        view.fitWidthProperty().bind(Bindings.max(1.0, pane.widthProperty().subtract(24.0)));
                        view.fitHeightProperty().bind(Bindings.max(1.0, pane.heightProperty().subtract(24.0)));
                        pane.getChildren().setAll(view);
                    }
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> pane.getChildren().setAll(new Label(UiText.text("ui.java.book_details_controller.1322c85709"))));
                    return null;
                });
        return pane;
    }

    private static byte[] readImage(RichBookDetailsSession session, String id) throws Exception {
        try (InputStream in = session.openImage(id).orElse(null)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_GALLERY_IMAGE_BYTES) return null;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    static List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,;|]"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String groupDisplayName(String name) {
        if (name == null) return "";
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "favorites" -> localizationService.text("ui.text.favorites");
            case "to read" -> localizationService.text("ui.text.to_read");
            default -> name;
        };
    }

    private static String formatTextStats(DocumentInspection document) {
        if (document == null || !document.parsed()) return UiText.text("ui.fxml.details.text");
        String chars = document.characterCount() > 0 ? String.format(Locale.ROOT, "%,d", document.characterCount()) : "—";
        String words = document.wordCount() > 0 ? String.format(Locale.ROOT, "%,d", document.wordCount()) : "—";
        return UiText.text("ui.java.book_details_controller.12828ab7d4") + chars + UiText.text("ui.java.book_details_controller.94985830fe") + words + UiText.text("ui.java.book_details_controller.43c0584623") + document.chapterCount() + UiText.text("ui.java.book_details_controller.bd30a55e75");
    }

    private static String year(BookDto book, DocumentInspection document) {
        if (book.getYear() != null && book.getYear() > 0) return String.valueOf(book.getYear());
        String fileYear = document == null ? "" : document.year();
        return fileYear == null || fileYear.isBlank() ? "—" : fileYear;
    }

    private static String detectFormat(BookDto book) {
        String name = firstNonBlank(book.getArchiveEntry(), book.getFileName());
        if (name.isBlank()) return "—";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "—";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void clearUI() {
        titleLabel.setText(UiText.text("ui.bulkedit.field.title"));
        authorsLinksPane.getChildren().setAll(new Label("—"));
        seriesLinksPane.getChildren().setAll(new Label("—"));
        genresLinksPane.getChildren().setAll(new Label("—"));
        languageLabel.setText(UiText.text("ui.fxml.details.language"));
        sourceLanguageLabel.setText(UiText.text("ui.fxml.details.original_language"));
        yearLabel.setText(UiText.text("ui.fxml.details.year"));
        publisherLink.setText(UiText.text("ui.fxml.details.publisher"));
        publisherLink.setDisable(true);
        isbnLabel.setText(UiText.text("ui.common.isbn_prefix") + "—");
        translatorsLabel.setText(UiText.text("ui.fxml.details.translators"));
        formatLabel.setText(UiText.text("ui.fxml.details.format"));
        fileSizeLabel.setText(UiText.text("ui.fxml.details.file_size"));
        artifactBadgesPane.getChildren().setAll(new Label("—"));
        artifactComboBox.getItems().clear();
        artifactComboBox.setDisable(true);
        artifactLocationLabel.setText(UiText.text("ui.fxml.details.source_location"));
        setPreferredArtifactButton.setDisable(true);
        openArtifactButton.setDisable(true);
        if (downloadBookButton != null) {
            downloadBookButton.setVisible(false);
            downloadBookButton.setManaged(false);
            downloadBookButton.setDisable(true);
        }
        textStatsLabel.setText(UiText.text("ui.fxml.details.text"));
        localStatusLabel.setText(UiText.text("ui.fxml.details.status"));
        ratingLabel.setText(UiText.text("ui.fxml.details.rating"));
        progressLabel.setText(UiText.text("ui.fxml.details.read"));
        libraryRateLabel.setText(UiText.text("ui.fxml.details.catalog_rating"));
        sourceUrlLabel.setText(UiText.text("ui.fxml.details.source_da86429"));
        annotationLabel.setText("");
        reviewArea.clear();
        keywordsLinksPane.getChildren().setAll(new Label("—"));
        groupLinksPane.getChildren().setAll(new Label("—"));
        reviewLinksPane.getChildren().setAll(new Label("—"));
        tocPreviewBox.getChildren().setAll(new Label("—"));
        if (similarBooksBox != null) similarBooksBox.getChildren().setAll(new Label("—"));
        imagesCountLabel.setText(UiText.text("ui.fxml.details.images_0"));
        showImagesButton.setDisable(true);
        showImagesButton.setVisible(false);
        if (scanDuplicatePagesButton != null) { scanDuplicatePagesButton.setVisible(false); scanDuplicatePagesButton.setManaged(false); }
        showImagesButton.setManaged(false);
        showWarning("");
    }

    private BookDto currentBook() {
        return currentSession != null ? currentSession.book() : appState.getBookDetails().getCurrentBook();
    }

    private void cancelDetailsAnalysis() {
        UiBackgroundExecutor.CancellableSubmission<RichBookDetailsSession> task = detailsAnalysisTask;
        detailsAnalysisTask = null;
        if (task != null) task.cancel();
    }

    private void cancelSimilarBooks() {
        UiBackgroundExecutor.CancellableSubmission<List<SimilarBookResult>> task = similarBooksTask;
        similarBooksTask = null;
        if (task != null) task.cancel();
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null && depth++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private void closeCurrentSession() {
        RichBookDetailsSession session = currentSession;
        currentSession = null;
        if (session != null) session.close();
    }

    @PreDestroy
    public void cleanup() {
        loadGeneration.incrementAndGet();
        cancelDetailsAnalysis();
        cancelSimilarBooks();
        coverPresenter.clearCover();
        BookDetailsViewModel vm = appState.getBookDetails();
        if (bookChangeListener != null) {
            vm.currentBookProperty().removeListener(bookChangeListener);
            bookChangeListener = null;
        }
        closeCurrentSession();
    }

    @FXML
    private void onRead() {
        BookDto book = currentBook();
        if (book != null) {
            navigationService.readBook(book);
        }
    }

    @FXML
    private void onEdit() {
        BookDto book = currentBook();
        if (book == null || book.getId() == null || book.getId().isBlank()) return;
        BookId id = BookId.fromString(book.getId());
        javafx.stage.Window owner = titleLabel != null && titleLabel.getScene() != null
                ? titleLabel.getScene().getWindow() : null;
        classicLibraryActionsService.editBook(owner, id, () -> {
            BookDto selected = appState.getBookDetails().getCurrentBook();
            if (selected != null && id.asString().equals(selected.getId())) loadBookDetails(selected);
        });
    }

    @FXML
    private void onOpenFolder() {
        BookDto book = currentBook();
        if (book != null) navigationService.openBookFolder(book);
    }
}