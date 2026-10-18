package com.myhomelibcorp.ui.reader;

import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.deleteTemp;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.flattenToc;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.isAudioExtension;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.isComicExtension;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.millis;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.rootMessage;
import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.unwrap;
import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.Utf16BoundarySupport;
import com.myhomelibcorp.application.reader.AudiobookTrackGrouping;
import com.myhomelibcorp.application.reader.ReaderSourceResolver;
import com.myhomelibcorp.application.annotation.AnnotationService;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.reader.ReaderPreferenceScope;
import com.myhomelibcorp.application.reader.ReaderSettingsState;
import com.myhomelibcorp.application.reader.ReaderSettingsStateService;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.application.service.ReadingHistoryService;
import com.myhomelibcorp.application.service.ReadingSessionService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ReaderAnnotationActivation;
import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderAnnotationType;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderSelection;
import com.myhomelibcorp.reader.api.TocEntry;
import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.audio.AudioDocumentSession;
import com.myhomelibcorp.reader.audio.AudioPosition;
import com.myhomelibcorp.reader.audio.AudioReaderView;
import com.myhomelibcorp.reader.audio.FfmpegAudioPlaybackBackend;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.core.ReaderEngine.PreparedBook;
import com.myhomelibcorp.reader.render.javafx.ReaderView;
import com.myhomelibcorp.reader.render.comic.ComicDocumentSession;
import com.myhomelibcorp.reader.render.comic.ComicPageSource;
import com.myhomelibcorp.reader.render.comic.ComicReaderView;
import com.myhomelibcorp.reader.render.pdf.PdfDocumentSession;
import com.myhomelibcorp.reader.render.pdf.PdfOutlineEntry;
import com.myhomelibcorp.reader.render.pdf.PdfPasswordRequiredException;
import com.myhomelibcorp.reader.render.pdf.PdfReaderView;
import com.myhomelibcorp.reader.render.pdf.PdfSearchOutcome;
import com.myhomelibcorp.reader.render.pdf.PdfSearchResult;
import com.myhomelibcorp.reader.service.ReaderSearchService;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.MainLayoutService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class NewReaderWorkspaceController implements WorkspaceLifecycle {

    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final BookResourcePort bookResourcePort;
    private final ApplicationSettingsPort settings;
    private final BookMapper bookMapper;
    private final NavigationService navigationService;
    private final SessionService sessionService;
    private final ReadingHistoryService readingHistoryService;
    private final ReadingSessionService readingSessionService;
    private final DialogService dialogService;
    private final NewReaderPersistenceService persistenceService;
    private final ReaderSettingsStateService readerSettingsStateService;
    private final AnnotationService annotationService;
    private final ReaderAnnotationCoordinator annotationCoordinator;
    private final WorkspaceManager workspaceManager;
    private final ReaderTextProviderCoordinator textProviderCoordinator;
    private final ReaderBookmarkCoordinator bookmarkCoordinator;
    private final ReaderBookMapCoordinator bookMapCoordinator;
    private final ReaderTtsCoordinator ttsCoordinator;
    private final UiBackgroundExecutor uiBackgroundExecutor;
    private final ApplicationState appState;
    private final MainLayoutService mainLayoutService;
    private final LocalizationService i18n;

    @FXML
    private StackPane readerContainer;
    @FXML private VBox annotationSidebar;
    @FXML private TabPane readerSidebarTabs;
    @FXML private Tab tocSidebarTab;
    @FXML private Tab searchSidebarTab;
    @FXML private Tab bookmarkSidebarTab;
    @FXML private Tab annotationSidebarTab;
    @FXML private Tab bookMapSidebarTab;
    @FXML private TextField tocSidebarSearch;
    @FXML private ListView<TocEntry> tocSidebarList;
    @FXML private TextField readerSidebarSearchField;
    @FXML private Label readerSidebarSearchStatus;
    @FXML private ListView<ReaderSearchService.SearchResult> readerSidebarSearchResults;
    @FXML private ListView<ReaderBookmarkItem> bookmarkSidebarList;
    @FXML private Button bookmarkSidebarGo;
    @FXML private Button bookmarkSidebarDelete;
    @FXML private TextField annotationSidebarSearch;
    @FXML private ComboBox<AnnotationTypeChoice> annotationSidebarTypeFilter;
    @FXML private ComboBox<AnnotationTagChoice> annotationSidebarTagFilter;
    @FXML private ListView<AnnotationSidebarRow> annotationSidebarList;
    @FXML private Label annotationSidebarCount;
    @FXML private Button annotationSidebarUnavailable;
    @FXML private Button annotationSidebarEdit;
    @FXML private Button annotationSidebarDelete;
    @FXML private ListView<ReaderBookMapCoordinator.BookMapRow> bookMapSidebarList;

    private ReaderView readerView;
    private PdfReaderView pdfReaderView;
    private ComicReaderView comicReaderView;
    private AudioReaderView audioReaderView;
    private boolean currentPdf;
    private boolean currentComic;
    private boolean currentAudio;
    private ProgressIndicator loadingIndicator;
    private BookDto currentBook;
    private BookId currentBookId;
    private String currentBookCollectionId;
    private String currentReaderArtifactId;
    private Path materializedBookFile;
    private volatile boolean isDisposed = false;
    private final AtomicLong openGeneration = new AtomicLong();
    private volatile Future<?> openTask;

    private ReaderPositionAutosaver positionAutosaver;
    private AudioPositionAutosaver audioPositionAutosaver;
    private boolean positionChanged = false;
    private ReaderSettingsState.Source currentSettingsSource = ReaderSettingsState.Source.GLOBAL;
    private String currentReaderFormat;
    private String annotationTargetId;
    private ReaderAnnotationPresentation currentAnnotationPresentation =
            new ReaderAnnotationPresentation(List.of(), List.of());
    private List<TocEntry> currentSidebarToc = List.of();
    private List<ReaderBookmarkItem> currentSidebarBookmarks = List.of();
    private ReaderSidebarSearchCoordinator sidebarSearchCoordinator;
    private String contentTargetArtifactId;
    private Long contentTargetOffset;

    @FXML
    public void initialize() {
        log.info("📖 NewReaderWorkspaceController ініціалізовано");
        positionAutosaver = new ReaderPositionAutosaver(persistenceService);
        audioPositionAutosaver = new AudioPositionAutosaver(persistenceService);
        configureReaderSidebar();
        initializeReaderView();
    }

    private void configureReaderSidebar() {
        // Configure every sidebar tool independently. A missing optional annotations control must
        // never prevent TOC/search/bookmarks/Book Map from being initialized.
        if (annotationSidebarList != null) {
            annotationSidebarList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(AnnotationSidebarRow row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("annotation-chapter-header");
                if (empty || row == null) {
                    setText(null);
                    setTooltip(null);
                    setDisable(false);
                    return;
                }
                if (row.header()) {
                    setText(row.chapterLabel());
                    setTooltip(null);
                    setWrapText(false);
                    setAccessibleText(row.chapterLabel());
                    setDisable(true);
                    if (!getStyleClass().contains("annotation-chapter-header")) {
                        getStyleClass().add("annotation-chapter-header");
                    }
                    return;
                }
                ReaderAnnotationOverlay item = row.annotation();
                String icon = item.note() ? "📝" : "▰";
                String warning = item.relocated() ? " ⚠" : "";
                String body = item.displayText().replaceAll("\\s+", " ").trim();
                if (body.length() > 150) {
                    int end = Utf16BoundarySupport.safeCut(body, 147);
                    body = body.substring(0, end) + "…";
                }
                String tagText = item.tags().isEmpty() ? "" : "\n#" + String.join(" #", item.tags());
                setText(icon + warning + " " + body + tagText);
                setWrapText(true);
                setAccessibleText((row.chapterLabel() + " " + body).trim());
                setTooltip(new javafx.scene.control.Tooltip(item.quote()));
                setDisable(false);
            }
            });
            annotationSidebarList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, row) -> {
                ReaderAnnotationOverlay item = row == null ? null : row.annotation();
                boolean selected = item != null;
                if (annotationSidebarEdit != null) annotationSidebarEdit.setDisable(!selected);
                if (annotationSidebarDelete != null) annotationSidebarDelete.setDisable(!selected);
                if (selected) goToAnnotation(item);
            });
        }
        if (annotationSidebarSearch != null) {
            annotationSidebarSearch.textProperty().addListener((obs, oldValue, value) -> refreshAnnotationSidebarList());
        }
        if (annotationSidebarTypeFilter != null) {
            annotationSidebarTypeFilter.getItems().setAll(
                    new AnnotationTypeChoice(null, i18n.text("ui.reader.annotation.sidebar.all_types")),
                    new AnnotationTypeChoice(ReaderAnnotationType.NOTE, i18n.text("ui.annotations.type.note")),
                    new AnnotationTypeChoice(ReaderAnnotationType.HIGHLIGHT, i18n.text("ui.annotations.type.highlight")));
            annotationSidebarTypeFilter.getSelectionModel().selectFirst();
            annotationSidebarTypeFilter.valueProperty().addListener((obs, oldValue, value) -> refreshAnnotationSidebarList());
        }
        if (annotationSidebarTagFilter != null) {
            annotationSidebarTagFilter.getItems().setAll(
                    new AnnotationTagChoice(null, i18n.text("ui.reader.annotation.sidebar.all_tags")));
            annotationSidebarTagFilter.getSelectionModel().selectFirst();
            annotationSidebarTagFilter.valueProperty().addListener((obs, oldValue, value) -> refreshAnnotationSidebarList());
        }
        if (tocSidebarSearch != null) {
            tocSidebarSearch.textProperty().addListener((obs, oldValue, value) -> refreshTocSidebarList());
        }
        if (tocSidebarList != null) {
            tocSidebarList.setCellFactory(view -> new ListCell<>() {
                @Override protected void updateItem(TocEntry item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) { setText(null); return; }
                    setText("  ".repeat(Math.max(0, Math.min(8, item.level()))) + item.title());
                }
            });
            tocSidebarList.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) goToSidebarTocEntry();
            });
        }
        if (readerSidebarSearchResults != null) {
            readerSidebarSearchResults.setCellFactory(view -> new ListCell<>() {
                @Override protected void updateItem(ReaderSearchService.SearchResult item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) { setText(null); setTooltip(null); return; }
                    String context = item.context() == null ? "" : item.context().replaceAll("\\s+", " ").trim();
                    if (context.length() > 160) {
                        int end = Utf16BoundarySupport.safeCut(context, 157);
                        context = context.substring(0, end) + "…";
                    }
                    setText(context);
                    setWrapText(true);
                    setTooltip(new javafx.scene.control.Tooltip(context));
                }
            });
            readerSidebarSearchResults.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) goToSidebarSearchResult();
            });
        }
        if (bookmarkSidebarList != null) {
            bookmarkSidebarList.setCellFactory(view -> new ListCell<>() {
                @Override protected void updateItem(ReaderBookmarkItem item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) { setText(null); return; }
                    setText(new BookmarkChoice(item, i18n.text("ui.reader.bookmark.default_title")).toString());
                }
            });
            bookmarkSidebarList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, item) -> {
                boolean selected = item != null;
                if (bookmarkSidebarGo != null) bookmarkSidebarGo.setDisable(!selected);
                if (bookmarkSidebarDelete != null) bookmarkSidebarDelete.setDisable(!selected);
            });
            bookmarkSidebarList.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) goToSidebarBookmark();
            });
        }
        if (bookMapSidebarList != null) {
            bookMapSidebarList.setCellFactory(view -> new ListCell<>() {
                @Override protected void updateItem(ReaderBookMapCoordinator.BookMapRow item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) { setText(null); return; }
                    setText(item.label());
                    setWrapText(true);
                }
            });
            bookMapSidebarList.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) goToBookMapRow();
            });
        }
    }

    private void initializeReaderView() {
        readerView = new ReaderView(i18n::text);
        readerView.setReducedMotion(settings.getBoolean("ui.accessibility.reducedMotion", false));
        readerView.setOnBackClick(this::onBack);
        readerView.setOnSettingsClick(this::showSettings);
        readerView.setOnSettingsChanged(this::persistReaderSettings);
        readerView.setOnBookmarkClick(this::addBookmark);
        readerView.setOnBookmarksClick(this::showBookmarks);
        readerView.setOnTocClick(this::showToc);
        readerView.setOnSearchClick(this::showSearch);
        readerView.setOnAnnotationsClick(this::toggleAnnotationSidebar);
        readerView.setOnBookMapClick(this::showBookMap);
        readerView.setOnHighlightRequested(this::createHighlightFromSelection);
        readerView.setOnNoteRequested(this::createNoteFromSelection);
        readerView.setOnAnnotationActivated(this::showAnnotationPopover);
        readerView.setOnDictionaryRequested(this::lookupDictionaryFromSelection);
        readerView.setOnTranslationRequested(this::translateSelection);
        readerView.setOnTtsStartClick(this::startTts);
        readerView.setOnTtsPauseClick(this::pauseResumeTts);
        readerView.setOnTtsStopClick(this::stopTts);
        readerView.updateTtsState(false, false);
        readerView.setOnToggleLeftSidebarClick(mainLayoutService::toggleLeftSidebar);
        readerView.setOnToggleRightSidebarClick(mainLayoutService::toggleRightSidebar);
        readerView.getCanvas().setOnPositionChanged(pos -> {
            positionChanged = true;
            if (positionAutosaver != null) positionAutosaver.mark(pos);
            if (annotationSidebar != null && annotationSidebar.isVisible() && readerSidebarTabs != null
                    && readerSidebarTabs.getSelectionModel().getSelectedItem() == bookMapSidebarTab) {
                refreshBookMapSidebar();
            }
        });

        pdfReaderView = new PdfReaderView(i18n::text);
        pdfReaderView.setVisible(false);
        pdfReaderView.setManaged(false);
        pdfReaderView.setOnBack(this::onBack);
        pdfReaderView.setOnAddBookmark(this::addBookmark);
        pdfReaderView.setOnBookmarks(this::showBookmarks);
        pdfReaderView.setOnToc(this::showToc);
        pdfReaderView.setOnSearch(this::showSearch);
        pdfReaderView.setOnPositionChanged(pos -> {
            positionChanged = true;
            if (positionAutosaver != null) positionAutosaver.mark(pos);
        });
        pdfReaderView.setOnError(error -> {
            log.warn("PDF Reader operation failed", error);
            dialogService.showError(i18n.text("common.error"),
                    i18n.format("ui.reader.pdf.render_failed", UiExceptionSupport.message(error)));
        });

        comicReaderView = new ComicReaderView(i18n::text);
        comicReaderView.setVisible(false);
        comicReaderView.setManaged(false);
        comicReaderView.setOnBack(this::onBack);
        comicReaderView.setOnAddBookmark(this::addBookmark);
        comicReaderView.setOnBookmarks(this::showBookmarks);
        comicReaderView.setOnPositionChanged(pos -> {
            positionChanged = true;
            if (positionAutosaver != null) positionAutosaver.mark(pos);
        });
        comicReaderView.setOnError(error -> {
            log.warn("Comic Reader operation failed", error);
            dialogService.showError(i18n.text("common.error"),
                    i18n.format("ui.reader.comic.render_failed", UiExceptionSupport.message(error)));
        });

        audioReaderView = new AudioReaderView(i18n::text);
        audioReaderView.setVisible(false);
        audioReaderView.setManaged(false);
        audioReaderView.setOnBack(this::onBack);
        audioReaderView.setOnAddBookmark(this::addBookmark);
        audioReaderView.setOnBookmarks(this::showBookmarks);
        audioReaderView.setOnPositionChanged(pos -> {
            positionChanged = true;
            if (audioPositionAutosaver != null) audioPositionAutosaver.mark(pos, audioReaderView.progressPercent());
        });
        audioReaderView.setOnError(error -> {
            log.warn("Audio Reader operation failed", error);
            dialogService.showError(i18n.text("common.error"),
                    i18n.format("ui.reader.audio.playback_failed", UiExceptionSupport.message(error)));
        });

        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(64, 64);
        loadingIndicator.setVisible(false);
        loadingIndicator.setManaged(false);
        if (readerContainer != null) {
            readerContainer.getChildren().setAll(readerView, pdfReaderView, comicReaderView, audioReaderView, loadingIndicator);
        }
    }

    /** Optional one-shot navigation target supplied by Annotation Manager before the book opens. */
    public void setAnnotationTargetId(String annotationId) {
        this.annotationTargetId = annotationId == null || annotationId.isBlank() ? null : annotationId.trim();
    }

    /** One-shot target supplied by global full-text search before the book opens. */
    public void setContentSearchTarget(String artifactId, Long offset) {
        this.contentTargetArtifactId = artifactId == null || artifactId.isBlank() ? null : artifactId.trim();
        this.contentTargetOffset = offset == null ? null : Math.max(0L, offset);
    }

    public void setBookId(BookId bookId) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setBookId(bookId));
            return;
        }
        if (bookId == null) {
            log.warn("❌ bookId is null");
            return;
        }
        if (isDisposed) {
            log.warn("❌ Controller вже знищено, пропускаємо");
            return;
        }

        beginBookOpen(bookId, null, true);
    }

    private void beginBookOpen(BookId bookId, String pdfPassword, boolean replaceCurrent) {
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(openGeneration, appState);
        cancelPendingOpen();
        if (replaceCurrent) closeCurrentBookForReplacement();
        setLoading(true);
        log.info("📖 Асинхронна підготовка книги: {}", bookId);

        try {
            ReaderEngine engine = readerView.getEngine();
            openTask = uiBackgroundExecutor.submitCollectionScopedCancellable(
                    LibraryOperationType.READER_STATE, () -> {
                try {
                    PreparedOpen prepared = prepareOpen(requestToken, bookId, engine, pdfPassword);
                    if (Thread.currentThread().isInterrupted()) {
                        prepared.closeAbandoned();
                        return null;
                    }
                    Platform.runLater(() -> applyPreparedOpen(requestToken, prepared));
                } catch (Throwable error) {
                    Platform.runLater(() -> handleOpenFailure(requestToken, bookId, error));
                }
                return null;
            });
        } catch (RejectedExecutionException e) {
            setLoading(false);
            dialogService.showError(i18n.text("common.error"), i18n.text("ui.reader.error.background_queue_full"));
        } catch (com.myhomelibcorp.application.operation.LibraryOperationConflictException busy) {
            setLoading(false);
            dialogService.showError(i18n.text("common.error"), UiExceptionSupport.message(busy));
        }
    }

    /**
     * Показує прогрес завантаження книги.
     */
    private void showDownloadProgress(UiAsyncRequestToken requestToken, double progress, String status) {
        Platform.runLater(() -> {
            if (isDisposed || !UiAsyncRequestGuard.isCurrent(requestToken, openGeneration, appState)) return;
            if (loadingIndicator != null) {
                loadingIndicator.setVisible(true);
                loadingIndicator.setManaged(true);
                if (progress > 0 && progress < 1) {
                    loadingIndicator.setProgress(progress);
                } else {
                    loadingIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                }
            }
            if (status != null && !status.isEmpty()) {
                appState.getStatusBar().setStatusText(status);
            }
        });
    }

    private PreparedOpen prepareOpen(UiAsyncRequestToken requestToken, BookId bookId, ReaderEngine engine, String pdfPassword) throws Exception {
        String collectionId = requestToken.collectionId();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Reader open cancelled");

        long openStarted = System.nanoTime();
        long resolveStarted = openStarted;
        showDownloadProgress(requestToken, -1, i18n.text("ui.reader.progress.loading_metadata"));

        BookDto dto = loadBookByIdUseCase.execute(bookId)
                .orElseThrow(() -> new IOException(i18n.format("ui.reader.error.book_not_found", bookId)));
        Book projectedBook = bookMapper.toDomain(dto);
        String targetArtifact = contentTargetArtifactId;

        showDownloadProgress(requestToken, 0.1, i18n.text("ui.reader.progress.finding_file"));
        ReaderSourceResolver.SourceChoice sourceChoice = resolveReadableReaderSource(projectedBook, targetArtifact, bookResourcePort)
                .orElseThrow(() -> new IOException(i18n.format("ui.reader.error.file_not_found", projectedBook.getFileName())));
        final Book book = sourceChoice.book();
        Path filePath = sourceChoice.container();
        boolean contentTargetResolved = targetArtifact == null || targetArtifact.equals(sourceChoice.artifactId());
        if (targetArtifact != null && !contentTargetResolved) {
            log.warn("Content-search artifact {} is not readable for book {}; falling back to artifact {}",
                    targetArtifact, bookId, sourceChoice.artifactId());
        } else if (sourceChoice.fallbackFromPreferred()) {
            log.warn("Preferred artifact {} is not readable for book {}; falling back to artifact {}",
                    projectedBook.getPreferredArtifactId(), bookId, sourceChoice.artifactId());
        }
        long resolveNanos = System.nanoTime() - resolveStarted;

        showDownloadProgress(requestToken, 0.3, i18n.text("ui.reader.progress.preparing_file"));
        long materializeStarted = System.nanoTime();
        ReaderSourceResolver.MaterializedSource materialized = materializeReaderEntryIfNeeded(book, filePath);
        long materializeNanos = System.nanoTime() - materializeStarted;
        PreparedBook preparedBook = null;
        PdfDocumentSession pdfDocument = null;
        ComicDocumentSession comicDocument = null;
        AudioDocumentSession audioDocument = null;
        try {
            BookSource source = new FileBookSource(materialized.readerPath(), book.getId().asString());
            ReaderSettingsState state = readerSettingsStateService.load(
                    book.getId().asString(), book.getSeries(), source.extension());
            ReaderSettings settings = ReaderSettingsMapper.fromDomain(state.preferences());
            Optional<ReaderPosition> savedPosition = Optional.empty();
            Optional<AudioPosition> savedAudioPosition = Optional.empty();
            String persistenceWarning = null;
            boolean audio = isAudioExtension(source.extension());
            try {
                if (audio) savedAudioPosition = persistenceService.loadAudioPosition(collectionId, book.getId().asString());
                else savedPosition = persistenceService.loadPosition(collectionId, book.getId().asString());
            } catch (RuntimeException persistenceError) {
                log.warn("Не вдалося завантажити позицію читання для {}", book.getId(), persistenceError);
                persistenceWarning = i18n.text("ui.reader.warning.position_load_failed");
            }

            showDownloadProgress(requestToken, 0.6, i18n.text("ui.reader.progress.analyzing_structure"));
            long parseStarted = System.nanoTime();
            if (audio) {
                audioDocument = openAudioSession(book, materialized.readerPath());
            } else if ("pdf".equalsIgnoreCase(source.extension())) {
                pdfDocument = PdfDocumentSession.open(source, pdfPassword);
            } else if (isComicExtension(source.extension())) {
                comicDocument = openComicSession(materialized.readerPath());
            } else {
                preparedBook = engine.prepare(source);
            }
            long parseNanos = System.nanoTime() - parseStarted;
            long bytes = Files.size(materialized.readerPath());
            ReaderOpenTiming timing = new ReaderOpenTiming(openStarted, resolveNanos, materializeNanos, parseNanos,
                    bytes, source.extension());

            showDownloadProgress(requestToken, 1.0, i18n.text("ui.reader.progress.ready"));
            return new PreparedOpen(dto, book, preparedBook, pdfDocument, comicDocument, audioDocument, materialized.temporaryPath(),
                    settings, state.source(), savedPosition, savedAudioPosition, persistenceWarning, contentTargetResolved, timing);
        } catch (Throwable e) {
            if (preparedBook != null) preparedBook.close();
            if (pdfDocument != null) {
                try { pdfDocument.close(); }
                catch (IOException closeError) { e.addSuppressed(closeError); }
            }
            if (comicDocument != null) comicDocument.close();
            if (audioDocument != null) audioDocument.close();
            deleteTemp(materialized.temporaryPath());
            throw e;
        }
    }

    /** Resolves a physically readable Reader projection without mutating the persisted preferred artifact. */
    static Optional<ReaderSourceResolver.SourceChoice> resolveReadableReaderSource(
            Book book, String requestedArtifactId, BookResourcePort bookResourcePort) {
        return ReaderSourceResolver.resolve(book, requestedArtifactId, bookResourcePort);
    }

    private void applyPreparedOpen(UiAsyncRequestToken requestToken, PreparedOpen prepared) {
        if (isDisposed || !UiAsyncRequestGuard.isCurrent(requestToken, openGeneration, appState)) {
            prepared.closeAbandoned();
            return;
        }
        long renderReadyStarted = System.nanoTime();
        try {
            currentBook = prepared.dto();
            currentBookId = prepared.book().getId();
            currentBookCollectionId = requestToken.collectionId();
            currentReaderArtifactId = ReaderSourceResolver.resolveOpenedArtifactId(prepared.book());
            // Reader is also a book workspace. Keep the shared right details panel bound to the
            // opened book, but do not fire the property again when navigation already selected
            // this same logical book. Re-emitting the same id restarts BookDetailsAnalysisService
            // and causes an avoidable second FB2/EPUB parse immediately after Reader open.
            appState.getBookDetails().setCurrentBookIfDifferentId(currentBook);
            materializedBookFile = prepared.temporaryPath();
            currentSettingsSource = prepared.settingsSource();
            currentReaderFormat = prepared.timing().format();
            positionChanged = false;

            if (loadingIndicator != null) {
                loadingIndicator.setVisible(false);
                loadingIndicator.setManaged(false);
            }

            currentPdf = prepared.pdfDocument() != null;
            currentComic = prepared.comicDocument() != null;
            currentAudio = prepared.audioDocument() != null;
            if (!prepared.contentTargetResolved()) {
                contentTargetOffset = null;
                contentTargetArtifactId = null;
            }

            if (currentPdf) {
                showPdfReader();
                pdfReaderView.openPrepared(prepared.pdfDocument(), prepared.savedPosition().orElse(null));
            } else if (currentComic) {
                showComicReader();
                comicReaderView.openPrepared(prepared.comicDocument(), prepared.savedPosition().orElse(null));
            } else if (currentAudio) {
                showAudioReader();
                audioReaderView.openPrepared(prepared.audioDocument(), prepared.savedAudioPosition().orElse(null));
            } else {
                showTextReader();
                readerView.applySettings(prepared.settings());
                readerView.openPrepared(prepared.preparedBook(), prepared.savedPosition().orElse(null));
                jumpToContentSearchTarget();
            }
            long totalTextLength = currentDocumentLength();
            if (currentAudio) audioPositionAutosaver.start(currentBookCollectionId, currentBookId.asString());
            else positionAutosaver.start(currentBookCollectionId, currentBookId.asString(), totalTextLength);
            readingSessionService.start(currentBookCollectionId, currentBookId.asString(), currentProgressPercent());
            setLoading(false);
            if (!currentPdf && !currentComic && !currentAudio) refreshAnnotationsAsync();
            if (prepared.persistenceWarning() != null) {
                appState.getStatusBar().setStatusText(prepared.persistenceWarning());
            }

            BookId openedId = currentBookId;
            try {
                uiBackgroundExecutor.submitCollectionScoped(LibraryOperationType.READER_STATE, () -> {
                    if (isDisposed || !UiAsyncRequestGuard.isCurrent(requestToken, openGeneration, appState)) return;
                    try {
                        sessionService.saveLastOpenedBookId(openedId.asString());
                    } catch (RuntimeException error) {
                        log.warn("Не вдалося зберегти останню відкриту книгу: {}", rootMessage(error));
                    }
                    try {
                        readingHistoryService.recordOpened(openedId);
                    } catch (RuntimeException error) {
                        // Reading history is auxiliary state and must never terminate the Reader background task.
                        log.warn("Не вдалося оновити історію читання: {}", rootMessage(error));
                    }
                });
            } catch (RuntimeException lifecycleBusy) {
                log.debug("Reader auxiliary state skipped because library lifecycle changed: {}", rootMessage(lifecycleBusy));
            }
            ReaderOpenTiming timing = prepared.timing();
            log.info("reader_open_timing resolve_ms={} materialize_ms={} parse_ms={} render_ready_ms={} total_ms={} format={} size_bytes={}",
                    millis(timing.resolveNanos()), millis(timing.materializeNanos()), millis(timing.parseNanos()),
                    millis(System.nanoTime() - renderReadyStarted), millis(System.nanoTime() - timing.openStartedNanos()),
                    timing.format(), timing.bytes());
            log.info("✅ Книгу відкрито в Reader: {}", currentBook.getTitle());
        } catch (Throwable error) {
            if (currentPdf && pdfReaderView != null && pdfReaderView.isOpen()) {
                // Once attached, PdfReaderView owns the session and closes it after queued render work.
                pdfReaderView.closeDocument();
            } else if (currentComic && comicReaderView != null && comicReaderView.isOpen()) {
                comicReaderView.closeDocument();
            } else if (currentAudio && audioReaderView != null && audioReaderView.isOpen()) {
                audioReaderView.closeDocument();
            } else {
                prepared.closeContent();
            }
            deleteTemp(prepared.temporaryPath());
            materializedBookFile = null;
            currentBook = null;
            currentBookId = null;
            currentBookCollectionId = null;
            currentSettingsSource = ReaderSettingsState.Source.GLOBAL;
            currentReaderFormat = null;
            currentReaderArtifactId = null;
            currentPdf = false;
            currentComic = false;
            currentAudio = false;
            setLoading(false);
            log.error("❌ Помилка підключення підготовленої книги", error);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.reader.error.open_failed", rootMessage(error)));
        }
    }

    private void handleOpenFailure(UiAsyncRequestToken requestToken, BookId bookId, Throwable error) {
        if (isDisposed || !UiAsyncRequestGuard.isCurrent(requestToken, openGeneration, appState)) return;
        setLoading(false);
        Throwable root = unwrap(error);
        if (root instanceof InterruptedException || root instanceof InterruptedIOException
                || root instanceof CancellationException) {
            log.debug("Reader open cancelled for {}", bookId);
            return;
        }
        if (root instanceof PdfPasswordRequiredException) {
            requestPdfPassword().ifPresent(password -> beginBookOpen(bookId, password, false));
            return;
        }
        log.error("❌ Помилка відкриття книги {}", bookId, root);
        dialogService.showError(i18n.text("common.error"), i18n.format("ui.reader.error.open_failed", rootMessage(root)));
    }

    private Optional<String> requestPdfPassword() {
        Dialog<String> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.reader.pdf.password.title"));
        dialog.setHeaderText(i18n.text("ui.reader.pdf.password.header"));
        PasswordField password = new PasswordField();
        password.setPromptText(i18n.text("ui.reader.pdf.password.prompt"));
        dialog.getDialogPane().setContent(password);
        ButtonType open = new ButtonType(i18n.text("ui.reader.pdf.password.open"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(open, ButtonType.CANCEL);
        javafx.scene.Node openButton = dialog.getDialogPane().lookupButton(open);
        openButton.disableProperty().bind(password.textProperty().isEmpty());
        dialog.setResultConverter(button -> button == open ? password.getText() : null);
        Optional<String> result = dialog.showAndWait().filter(value -> value != null && !value.isBlank());
        password.clear();
        return result;
    }

    private void closeCurrentBookForReplacement() {
        annotationCoordinator.hidePopover();
        cancelTextProviderRequests();
        stopTts();
        if (currentBookId == null && (readerView == null || !readerView.isBookOpen())
                && (pdfReaderView == null || !pdfReaderView.isOpen())
                && (comicReaderView == null || !comicReaderView.isOpen())
                && (audioReaderView == null || !audioReaderView.isOpen())) return;
        savePosition();
        finishReadingSession();
        if (readerView != null && readerView.isBookOpen()) readerView.closeBook();
        if (pdfReaderView != null && pdfReaderView.isOpen()) pdfReaderView.closeDocument();
        if (comicReaderView != null && comicReaderView.isOpen()) comicReaderView.closeDocument();
        if (audioReaderView != null && audioReaderView.isOpen()) audioReaderView.closeDocument();
        cleanupMaterializedBookFile();
        currentBook = null;
        currentBookId = null;
        currentBookCollectionId = null;
        currentSettingsSource = ReaderSettingsState.Source.GLOBAL;
        currentReaderFormat = null;
        currentReaderArtifactId = null;
        refreshAnnotationSidebar(new ReaderAnnotationPresentation(List.of(), List.of()));
        currentPdf = false;
        currentComic = false;
        currentAudio = false;
        positionChanged = false;
    }

    private void cancelPendingOpen() {
        Future<?> task = openTask;
        openTask = null;
        if (task != null && !task.isDone()) task.cancel(true);
    }

    private void setLoading(boolean loading) {
        if (loadingIndicator != null) {
            loadingIndicator.setVisible(loading);
            loadingIndicator.setManaged(loading);
        }
        if (readerView != null) readerView.setDisable(loading);
        if (pdfReaderView != null) pdfReaderView.setDisable(loading);
        if (comicReaderView != null) comicReaderView.setDisable(loading);
        if (audioReaderView != null) audioReaderView.setDisable(loading);
    }

    private void showPdfReader() {
        if (readerView != null) { readerView.setVisible(false); readerView.setManaged(false); }
        if (comicReaderView != null) { comicReaderView.setVisible(false); comicReaderView.setManaged(false); }
        if (audioReaderView != null) { audioReaderView.setVisible(false); audioReaderView.setManaged(false); }
        if (pdfReaderView != null) { pdfReaderView.setVisible(true); pdfReaderView.setManaged(true); }
    }

    private void showComicReader() {
        if (readerView != null) { readerView.setVisible(false); readerView.setManaged(false); }
        if (pdfReaderView != null) { pdfReaderView.setVisible(false); pdfReaderView.setManaged(false); }
        if (audioReaderView != null) { audioReaderView.setVisible(false); audioReaderView.setManaged(false); }
        if (comicReaderView != null) { comicReaderView.setVisible(true); comicReaderView.setManaged(true); }
    }

    private void showTextReader() {
        if (pdfReaderView != null) { pdfReaderView.setVisible(false); pdfReaderView.setManaged(false); }
        if (comicReaderView != null) { comicReaderView.setVisible(false); comicReaderView.setManaged(false); }
        if (audioReaderView != null) { audioReaderView.setVisible(false); audioReaderView.setManaged(false); }
        if (readerView != null) { readerView.setVisible(true); readerView.setManaged(true); }
    }

    private void showAudioReader() {
        if (readerView != null) { readerView.setVisible(false); readerView.setManaged(false); }
        if (pdfReaderView != null) { pdfReaderView.setVisible(false); pdfReaderView.setManaged(false); }
        if (comicReaderView != null) { comicReaderView.setVisible(false); comicReaderView.setManaged(false); }
        if (audioReaderView != null) { audioReaderView.setVisible(true); audioReaderView.setManaged(true); }
    }

    private ComicDocumentSession openComicSession(Path archivePath) throws IOException {
        ComicPageSource source = new ComicPageSource() {
            @Override
            public List<String> listPageEntries() throws IOException {
                try {
                    return bookResourcePort.listArchiveEntries(archivePath);
                } catch (UncheckedIOException error) {
                    throw error.getCause();
                } catch (RuntimeException error) {
                    throw new IOException("Unable to enumerate comic archive: " + archivePath, error);
                }
            }

            @Override
            public InputStream openPage(String entryName) throws IOException {
                try {
                    return bookResourcePort.readArchiveEntry(archivePath, entryName)
                            .orElseThrow(() -> new IOException("Comic page is unavailable: " + entryName));
                } catch (UncheckedIOException error) {
                    throw error.getCause();
                } catch (RuntimeException error) {
                    throw new IOException("Unable to read comic page: " + entryName, error);
                }
            }
        };
        return ComicDocumentSession.open(source);
    }

    private AudioDocumentSession openAudioSession(Book book, Path primaryPath) throws IOException {
        List<Path> tracks = resolveAudiobookTracks(book, primaryPath);
        String ffprobe = System.getProperty("reader.audio.ffprobe", "ffprobe");
        String ffplay = System.getProperty("reader.audio.ffplay", "ffplay");
        FfmpegAudioPlaybackBackend backend = new FfmpegAudioPlaybackBackend(ffprobe, ffplay, Duration.ofSeconds(8));
        if (!backend.available()) {
            throw new IOException(i18n.text("ui.reader.audio.backend_unavailable"));
        }
        return AudioDocumentSession.open(tracks, backend);
    }

    /** Groups only explicitly tagged local artifacts; alternate MP3/M4B representations are not merged accidentally. */
    private List<Path> resolveAudiobookTracks(Book book, Path primaryPath) {
        return AudiobookTrackGrouping.resolve(book, primaryPath, bookResourcePort::locateBookContainer);
    }

    private ReaderSourceResolver.MaterializedSource materializeReaderEntryIfNeeded(Book book, Path physicalPath) throws IOException {
        return ReaderSourceResolver.materialize(book, physicalPath, bookResourcePort,
                i18n.format("ui.reader.error.archive_entry_read", book.getArchiveEntry()));
    }

    private record ReaderOpenTiming(long openStartedNanos, long resolveNanos, long materializeNanos,
                                    long parseNanos, long bytes, String format) { }

    private record PreparedOpen(
            BookDto dto, Book book, PreparedBook preparedBook, PdfDocumentSession pdfDocument,
            ComicDocumentSession comicDocument, AudioDocumentSession audioDocument, Path temporaryPath,
            ReaderSettings settings, ReaderSettingsState.Source settingsSource, Optional<ReaderPosition> savedPosition,
            Optional<AudioPosition> savedAudioPosition, String persistenceWarning, boolean contentTargetResolved,
            ReaderOpenTiming timing) {
        void closeContent() {
            if (preparedBook != null) preparedBook.close();
            if (pdfDocument != null) {
                try { pdfDocument.close(); } catch (IOException ignored) { }
            }
            if (comicDocument != null) comicDocument.close();
            if (audioDocument != null) audioDocument.close();
        }
        void closeAbandoned() {
            closeContent();
            deleteTemp(temporaryPath);
        }
    }

    private void cleanupMaterializedBookFile() {
        Path temp = materializedBookFile;
        materializedBookFile = null;
        if (temp != null) {
            try { Files.deleteIfExists(temp); }
            catch (IOException e) { log.debug("Не вдалося видалити тимчасову книгу {}: {}", temp, e.getMessage()); }
        }
    }

    private void savePosition() {
        boolean textOpen = readerView != null && readerView.isBookOpen();
        boolean pdfOpen = pdfReaderView != null && pdfReaderView.isOpen();
        boolean comicOpen = comicReaderView != null && comicReaderView.isOpen();
        boolean audioOpen = audioReaderView != null && audioReaderView.isOpen();
        if ((!textOpen && !pdfOpen && !comicOpen && !audioOpen) || currentBookId == null) return;
        try {
            boolean saved;
            if (currentAudio && audioOpen) {
                AudioPosition pos = audioReaderView.currentPosition();
                if (audioPositionAutosaver != null) { audioPositionAutosaver.mark(pos, audioReaderView.progressPercent()); saved = audioPositionAutosaver.flush(); }
                else saved = persistenceService.saveAudioPosition(currentBookCollectionId, currentBookId.asString(), pos, audioReaderView.progressPercent());
            } else {
                ReaderPosition pos = currentPdf && pdfOpen ? pdfReaderView.currentPosition()
                        : currentComic && comicOpen ? comicReaderView.currentPosition()
                        : readerView.getCurrentPosition();
                if (pos == null) return;
                if (positionAutosaver != null) { positionAutosaver.mark(pos); saved = positionAutosaver.flush(); }
                else saved = persistenceService.savePosition(currentBookCollectionId, currentBookId.asString(), pos, currentDocumentLength());
            }
            if (!saved) {
                appState.getStatusBar().setStatusText(i18n.text("ui.reader.warning.position_save_retry"));
            } else {
                updateVisibleProgress(currentProgressPercent());
            }
        } catch (Exception e) {
            log.warn("Не вдалося зберегти позицію: {}", e.getMessage());
        }
    }

    private void updateVisibleProgress(int progress) {
        int safeProgress = Math.max(0, Math.min(100, progress));
        String bookId = currentBookId == null ? null : currentBookId.asString();
        if (bookId == null) return;
        UiExecutor.runOnUiThread(() -> {
            if (!java.util.Objects.equals(currentBookCollectionId, appState.getCurrentLibraryCollectionId())) return;
            appState.getBookTable().getBooks().stream()
                    .filter(row -> bookId.equals(row.getId()))
                    .findFirst()
                    .ifPresent(row -> row.setProgress(safeProgress));
            BookDto details = appState.getBookDetails().getCurrentBook();
            if (details != null && bookId.equals(details.getId())) details.setProgress(safeProgress);
        });
    }

    @FXML
    private void onBack() {
        stopTts();
        if (isDisposed) {
            return;
        }

        if (positionChanged) {
            savePosition();
            positionChanged = false;
        } else {
            savePosition();
        }

        finishReadingSession();
        if (readerView != null && readerView.isBookOpen()) readerView.closeBook();
        if (pdfReaderView != null && pdfReaderView.isOpen()) pdfReaderView.closeDocument();
        if (comicReaderView != null && comicReaderView.isOpen()) comicReaderView.closeDocument();
        if (audioReaderView != null && audioReaderView.isOpen()) audioReaderView.closeDocument();
        cleanupMaterializedBookFile();

        navigationService.goBack();
    }

    private void finishReadingSession() {
        if (currentBookId == null) return;
        readingSessionService.finish(currentBookCollectionId, currentBookId.asString(), currentProgressPercent());
    }

    private long currentDocumentLength() {
        if (currentAudio && audioReaderView != null && audioReaderView.isOpen()) return Math.max(1L, audioReaderView.totalDurationMillis());
        if (currentPdf && pdfReaderView != null && pdfReaderView.isOpen()) return Math.max(1L, pdfReaderView.pageCount() - 1L);
        if (currentComic && comicReaderView != null && comicReaderView.isOpen()) return Math.max(1L, comicReaderView.pageCount() - 1L);
        if (readerView == null || readerView.getEngine().getCurrentDocument() == null) return 0L;
        return Math.max(0L, readerView.getEngine().getCurrentDocument().totalTextLength());
    }

    private int currentProgressPercent() {
        if (currentAudio && audioReaderView != null && audioReaderView.isOpen()) return Math.max(0, Math.min(100, (int) Math.round(audioReaderView.progressPercent())));
        if (currentPdf && pdfReaderView != null && pdfReaderView.isOpen()) return Math.max(0, Math.min(100, (int) Math.round(pdfReaderView.progressPercent())));
        if (currentComic && comicReaderView != null && comicReaderView.isOpen()) return Math.max(0, Math.min(100, (int) Math.round(comicReaderView.progressPercent())));
        if (readerView == null || !readerView.isBookOpen()) return 0;
        ReaderPosition position = readerView.getCurrentPosition();
        return position == null ? 0 : percentForPosition(position);
    }

    private int percentForPosition(ReaderPosition position) {
        if (position == null) return 0;
        long total = currentDocumentLength();
        if (total <= 0) return 0;
        return Math.max(0, Math.min(100, (int) Math.round(position.getPercent(total))));
    }

    private void persistReaderSettings(ReaderSettings settings) {
        ReaderSettingsDialog.SaveTarget target = switch (currentSettingsSource) {
            case BOOK -> ReaderSettingsDialog.SaveTarget.BOOK;
            case SERIES -> ReaderSettingsDialog.SaveTarget.SERIES;
            case FORMAT -> ReaderSettingsDialog.SaveTarget.FORMAT;
            case GLOBAL -> ReaderSettingsDialog.SaveTarget.GLOBAL;
        };
        persistReaderSettings(settings, target);
    }

    private void persistReaderSettings(ReaderSettings settings, ReaderSettingsDialog.SaveTarget target) {
        if (target == null) return;
        String bookId = currentBookId != null ? currentBookId.asString() : null;
        String series = currentBook == null ? null : currentBook.getSeries();
        String format = currentReaderFormat;
        try {
            if (target == ReaderSettingsDialog.SaveTarget.CLEAR_OVERRIDE) {
                clearCurrentReaderOverride(bookId, series, format);
            } else {
                if (settings == null) return;
                ReaderPreferences previous;
                switch (target) {
                    case GLOBAL -> {
                        previous = readerSettingsStateService.loadGlobal();
                        readerSettingsStateService.saveGlobal(ReaderSettingsMapper.toDomain(settings, previous));
                    }
                    case FORMAT -> {
                        ReaderPreferenceScope scope = ReaderPreferenceScope.tryFormat(format);
                        if (scope == null) return;
                        previous = readerSettingsStateService.loadScope(scope)
                                .orElseGet(() -> readerSettingsStateService.load(bookId, series, format).preferences());
                        readerSettingsStateService.saveForFormat(format, ReaderSettingsMapper.toDomain(settings, previous));
                    }
                    case SERIES -> {
                        ReaderPreferenceScope scope = ReaderPreferenceScope.trySeries(series);
                        if (scope == null) return;
                        previous = readerSettingsStateService.loadScope(scope)
                                .orElseGet(() -> readerSettingsStateService.load(bookId, series, format).preferences());
                        readerSettingsStateService.saveForSeries(series, ReaderSettingsMapper.toDomain(settings, previous));
                    }
                    case BOOK -> {
                        ReaderPreferenceScope scope = ReaderPreferenceScope.tryBook(bookId);
                        if (scope == null) return;
                        previous = readerSettingsStateService.loadScope(scope)
                                .orElseGet(() -> readerSettingsStateService.load(bookId, series, format).preferences());
                        readerSettingsStateService.saveForBook(bookId, ReaderSettingsMapper.toDomain(settings, previous));
                    }
                    case CLEAR_OVERRIDE -> throw new IllegalStateException("Handled above");
                }
            }
            applyResolvedReaderSettings(bookId, series, format);
        } catch (RuntimeException error) {
            log.error("Не вдалося зберегти scoped налаштування Reader", error);
            appState.getStatusBar().setStatusText(i18n.text("ui.reader.warning.settings_save_failed"));
            try { applyResolvedReaderSettings(bookId, series, format); } catch (RuntimeException ignored) { }
        }
    }

    private void clearCurrentReaderOverride(String bookId, String series, String format) {
        switch (currentSettingsSource) {
            case BOOK -> readerSettingsStateService.clearBookOverride(bookId);
            case SERIES -> readerSettingsStateService.clearSeriesOverride(series);
            case FORMAT -> readerSettingsStateService.clearFormatOverride(format);
            case GLOBAL -> { }
        }
    }

    private void applyResolvedReaderSettings(String bookId, String series, String format) {
        ReaderSettingsState resolved = readerSettingsStateService.load(bookId, series, format);
        currentSettingsSource = resolved.source();
        if (readerView != null) readerView.applySettings(ReaderSettingsMapper.fromDomain(resolved.preferences()));
    }

    private void showSettings(ReaderSettings settings) {
        if (isDisposed || readerView == null) return;
        javafx.stage.Window owner = readerContainer != null && readerContainer.getScene() != null
                ? readerContainer.getScene().getWindow()
                : null;

        String series = currentBook == null ? null : currentBook.getSeries();
        ReaderSettingsDialog.ScopeContext scope = new ReaderSettingsDialog.ScopeContext(
                currentBookId != null,
                series != null && !series.isBlank(),
                currentReaderFormat != null && !currentReaderFormat.isBlank(),
                currentSettingsSource);

        ReaderSettingsDialog.show(owner, settings, scope, readerView::applySettings, i18n)
                .ifPresent(result -> persistReaderSettings(result.settings(), result.target()));
    }

    private void startTts() {
        if (isDisposed || currentPdf || currentComic || currentAudio || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        ReaderPosition position = readerView.getCurrentPosition();
        if (document == null || position == null) return;
        String bookId = currentBookId == null ? "" : currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        ReaderTtsCoordinator.Context context = new ReaderTtsCoordinator.Context(
                document,
                position,
                () -> !isDisposed && !currentPdf && !currentComic && !currentAudio
                        && readerView != null && readerView.isBookOpen()
                        && sameOpenBook(requestToken, bookId),
                (active, paused) -> {
                    if (readerView != null) readerView.updateTtsState(active, paused);
                },
                () -> {
                    if (readerView != null) readerView.clearSpeechHighlight();
                },
                (target, sentence) -> {
                    if (readerView == null || !readerView.isBookOpen()) return;
                    readerView.goToPosition(target);
                    readerView.setSpeechHighlight(sentence.startOffset(), sentence.endOffset());
                });
        ttsCoordinator.start(context);
    }

    private void pauseResumeTts() {
        ttsCoordinator.pauseResume();
    }

    private void stopTts() {
        ttsCoordinator.stop();
        if (readerView != null) {
            readerView.clearSpeechHighlight();
            readerView.updateTtsState(false, false);
        }
    }

    private void lookupDictionaryFromSelection(ReaderSelection selection) {
        if (!selectionActionAvailable(selection)) return;
        textProviderCoordinator.lookupDictionary(textProviderContext(), selection);
    }

    private void translateSelection(ReaderSelection selection) {
        if (!selectionActionAvailable(selection)) return;
        textProviderCoordinator.translate(textProviderContext(), selection);
    }

    private ReaderTextProviderCoordinator.Context textProviderContext() {
        String bookId = currentBookId == null ? "" : currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        String language = currentBook == null || currentBook.getLanguage() == null ? "" : currentBook.getLanguage();
        return new ReaderTextProviderCoordinator.Context(
                bookId, language, () -> sameOpenBook(requestToken, bookId));
    }

    private boolean selectionActionAvailable(ReaderSelection selection) {
        return !isDisposed
                && selection != null
                && selection.text() != null
                && !selection.text().isBlank()
                && readerView != null
                && readerView.isBookOpen()
                && currentBookId != null
                && currentBookBelongsToActiveCollection();
    }

    private void cancelTextProviderRequests() {
        textProviderCoordinator.cancelRequests();
    }

    private record AnnotationIssueChoice(ReaderAnnotationUnavailable issue, String label) {
        @Override public String toString() { return label; }
    }

    private record AnnotationSidebarRow(String chapterLabel, ReaderAnnotationOverlay annotation, boolean header) {
        private static AnnotationSidebarRow header(String chapterLabel) {
            return new AnnotationSidebarRow(chapterLabel, null, true);
        }

        private static AnnotationSidebarRow annotation(String chapterLabel, ReaderAnnotationOverlay annotation) {
            return new AnnotationSidebarRow(chapterLabel, annotation, false);
        }
    }

    private record AnnotationTypeChoice(ReaderAnnotationType type, String label) {
        @Override public String toString() { return label; }
    }

    private record AnnotationTagChoice(String tag, String label) {
        @Override public String toString() { return label; }
    }

    private void toggleAnnotationSidebar() {
        boolean sameTabVisible = annotationSidebar != null && annotationSidebar.isVisible()
                && readerSidebarTabs != null && readerSidebarTabs.getSelectionModel().getSelectedItem() == annotationSidebarTab;
        if (sameTabVisible) {
            closeAnnotationSidebar();
            return;
        }
        showReaderSidebar(annotationSidebarTab);
        refreshAnnotationSidebarList();
        if (annotationSidebarSearch != null) annotationSidebarSearch.requestFocus();
    }

    private void showReaderSidebar(Tab tab) {
        if (annotationSidebar == null) return;
        annotationSidebar.setVisible(true);
        annotationSidebar.setManaged(true);
        if (readerSidebarTabs != null && tab != null) readerSidebarTabs.getSelectionModel().select(tab);
    }

    @FXML
    public void closeAnnotationSidebar() {
        if (annotationSidebar != null) {
            annotationSidebar.setVisible(false);
            annotationSidebar.setManaged(false);
        }
    }

    @FXML
    public void editSidebarAnnotation() {
        AnnotationSidebarRow row = annotationSidebarList == null ? null
                : annotationSidebarList.getSelectionModel().getSelectedItem();
        if (row != null && row.annotation() != null) editAnnotation(row.annotation());
    }

    @FXML
    public void deleteSidebarAnnotation() {
        AnnotationSidebarRow row = annotationSidebarList == null ? null
                : annotationSidebarList.getSelectionModel().getSelectedItem();
        if (row != null && row.annotation() != null) deleteAnnotation(row.annotation());
    }

    @FXML
    public void openAnnotationManager() {
        workspaceManager.showAnnotationManagerWorkspace();
    }

    @FXML
    public void reviewAnnotationIssues() {
        List<ReaderAnnotationUnavailable> issues = currentAnnotationPresentation.unavailable();
        if (issues.isEmpty()) return;
        List<AnnotationIssueChoice> choices = issues.stream()
                .map(issue -> new AnnotationIssueChoice(issue, annotationIssueLabel(issue)))
                .toList();
        ChoiceDialog<AnnotationIssueChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.reader.annotation.issues.title"));
        dialog.setHeaderText(i18n.format("ui.reader.annotation.issues.header", choices.size()));
        dialog.setContentText(i18n.text("ui.reader.annotation.issues.select"));
        AnnotationIssueChoice selected = dialog.showAndWait().orElse(null);
        if (selected == null) return;
        ReaderAnnotationUnavailable issue = selected.issue();
        if (!issue.canOfferSafeRebind()) {
            String key = issue.reason() == ReaderAnnotationUnavailableReason.ARTIFACT_MISMATCH
                    ? "ui.reader.annotation.issues.ambiguous" : "ui.reader.annotation.issues.unresolved";
            dialogService.showInfo(i18n.text("ui.reader.annotation.issues.title"), i18n.text(key));
            return;
        }
        rebindUnavailableAnnotation(issue);
    }

    private String annotationIssueLabel(ReaderAnnotationUnavailable issue) {
        String kind = issue.reason() == ReaderAnnotationUnavailableReason.ARTIFACT_MISMATCH
                ? i18n.text("ui.reader.annotation.issues.artifact_mismatch")
                : i18n.text("ui.reader.annotation.issues.unresolved_short");
        String text = issue.source() == null ? issue.id()
                : (issue.source().noteText().isBlank() ? issue.source().anchor().quote() : issue.source().noteText());
        text = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (text.length() > 90) {
            int end = Utf16BoundarySupport.safeCut(text, 87);
            text = text.substring(0, end) + "…";
        }
        String safe = issue.canOfferSafeRebind() ? " ✓" : "";
        return kind + safe + " — " + text;
    }

    private void rebindUnavailableAnnotation(ReaderAnnotationUnavailable issue) {
        ReaderAnnotationCoordinator.Context context = annotationContext();
        if (context != null) annotationCoordinator.rebind(context, issue);
    }

    private void goToAnnotation(ReaderAnnotationOverlay item) {
        if (item == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null) return;
        long offset = Math.max(0L, Math.min(item.startOffset(), Math.max(0L, document.totalTextLength() - 1L)));
        ReaderPosition position = new ReaderPosition(Math.max(0, document.chapterIndexAt(offset)), offset, 0, 0);
        readerView.goToPosition(position);
        positionChanged = true;
        if (positionAutosaver != null) positionAutosaver.mark(position);
    }

    private void refreshAnnotationSidebar(ReaderAnnotationPresentation presentation) {
        currentAnnotationPresentation = presentation == null
                ? new ReaderAnnotationPresentation(List.of(), List.of()) : presentation;
        refreshAnnotationTagFilter();
        refreshAnnotationSidebarList();
        refreshBookMapSidebar();
    }

    private void refreshAnnotationTagFilter() {
        if (annotationSidebarTagFilter == null) return;
        String selected = annotationSidebarTagFilter.getValue() == null
                ? null : annotationSidebarTagFilter.getValue().tag();
        List<AnnotationTagChoice> choices = new ArrayList<>();
        choices.add(new AnnotationTagChoice(null, i18n.text("ui.reader.annotation.sidebar.all_tags")));
        ReaderAnnotationSidebarFilter.tags(currentAnnotationPresentation)
                .forEach(tag -> choices.add(new AnnotationTagChoice(tag, "#" + tag)));
        annotationSidebarTagFilter.getItems().setAll(choices);
        choices.stream().filter(choice -> Objects.equals(choice.tag(), selected)).findFirst()
                .ifPresentOrElse(annotationSidebarTagFilter::setValue,
                        () -> annotationSidebarTagFilter.getSelectionModel().selectFirst());
    }

    private void refreshAnnotationSidebarList() {
        if (annotationSidebarList == null) return;
        String query = annotationSidebarSearch == null ? null : annotationSidebarSearch.getText();
        ReaderAnnotationType type = annotationSidebarTypeFilter == null || annotationSidebarTypeFilter.getValue() == null
                ? null : annotationSidebarTypeFilter.getValue().type();
        String tag = annotationSidebarTagFilter == null || annotationSidebarTagFilter.getValue() == null
                ? null : annotationSidebarTagFilter.getValue().tag();
        List<ReaderAnnotationOverlay> items = ReaderAnnotationSidebarFilter.select(
                currentAnnotationPresentation, query, type, tag);
        AnnotationSidebarRow selectedRow = annotationSidebarList.getSelectionModel().getSelectedItem();
        String selectedId = selectedRow == null || selectedRow.annotation() == null ? null : selectedRow.annotation().id();
        List<AnnotationSidebarRow> rows = buildAnnotationSidebarRows(items);
        annotationSidebarList.setItems(javafx.collections.FXCollections.observableArrayList(rows));
        if (selectedId != null) {
            rows.stream().filter(row -> row.annotation() != null && selectedId.equals(row.annotation().id())).findFirst()
                    .ifPresent(annotationSidebarList.getSelectionModel()::select);
        }
        if (annotationSidebarCount != null) {
            annotationSidebarCount.setText(i18n.format("ui.reader.annotation.sidebar.count", items.size(),
                    currentAnnotationPresentation.overlays().size()));
        }
        if (annotationSidebarUnavailable != null) {
            int unavailable = currentAnnotationPresentation.unavailable().size();
            annotationSidebarUnavailable.setText(unavailable == 0 ? ""
                    : i18n.format("ui.reader.annotation.sidebar.unavailable", unavailable));
            annotationSidebarUnavailable.setVisible(unavailable > 0);
            annotationSidebarUnavailable.setManaged(unavailable > 0);
        }
    }

    private List<AnnotationSidebarRow> buildAnnotationSidebarRows(List<ReaderAnnotationOverlay> items) {
        if (items == null || items.isEmpty()) return List.of();
        ReaderDocument document = readerView == null || !readerView.isBookOpen()
                ? null : readerView.getEngine().getCurrentDocument();
        List<AnnotationSidebarRow> rows = new ArrayList<>();
        Integer previousChapter = null;
        for (ReaderAnnotationOverlay item : items) {
            int chapterIndex = document == null ? -1 : Math.max(0, document.chapterIndexAt(item.startOffset()));
            if (!Objects.equals(previousChapter, chapterIndex)) {
                rows.add(AnnotationSidebarRow.header(annotationSidebarChapterLabel(document, chapterIndex, item)));
                previousChapter = chapterIndex;
            }
            rows.add(AnnotationSidebarRow.annotation(annotationSidebarChapterLabel(document, chapterIndex, item), item));
        }
        return List.copyOf(rows);
    }

    private String annotationSidebarChapterLabel(ReaderDocument document, int chapterIndex, ReaderAnnotationOverlay item) {
        List<ChapterIndex> chapters = document == null ? null : document.chapters();
        if (chapters != null && chapterIndex >= 0 && chapterIndex < chapters.size()) {
            ChapterIndex chapter = chapters.get(chapterIndex);
            String title = chapter == null ? null : chapter.title();
            if (title != null && !title.isBlank()) return title;
        }
        if (item != null && item.chapterTitle() != null && !item.chapterTitle().isBlank()) return item.chapterTitle();
        return i18n.text("ui.annotations.digest_no_chapter");
    }

    private void createHighlightFromSelection(ReaderSelection selection) {
        if (!annotationActionAvailable(selection)) return;
        ReaderAnnotationCoordinator.Context context = annotationContext();
        if (context != null) annotationCoordinator.createHighlight(context, selection);
    }

    private void createNoteFromSelection(ReaderSelection selection) {
        if (!annotationActionAvailable(selection)) return;
        ReaderAnnotationCoordinator.Context context = annotationContext();
        if (context != null) annotationCoordinator.createNote(context, selection);
    }

    private void showAnnotationPopover(ReaderAnnotationActivation activation) {
        if (activation == null || activation.annotation() == null || isDisposed) return;
        ReaderAnnotationCoordinator.Context context = annotationContext();
        if (context != null) annotationCoordinator.showPopover(context, activation);
    }

    private void editAnnotation(ReaderAnnotationOverlay annotation) {
        ReaderAnnotationCoordinator.Context context = annotationContext();
        if (context != null) annotationCoordinator.edit(context, annotation);
    }

    private void deleteAnnotation(ReaderAnnotationOverlay annotation) {
        ReaderAnnotationCoordinator.Context context = annotationContext();
        if (context != null) annotationCoordinator.delete(context, annotation);
    }

    private void reanchorAnnotation(ReaderAnnotationOverlay annotation) {
        ReaderAnnotationCoordinator.Context context = annotationContext();
        if (context != null) annotationCoordinator.reanchor(context, annotation);
    }

    private Set<String> knownAnnotationTags() {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (ReaderAnnotationOverlay item : currentAnnotationPresentation.overlays()) tags.addAll(item.tags());
        return Set.copyOf(tags);
    }

    private ReaderAnnotationCoordinator.Context annotationContext() {
        if (isDisposed || currentBookId == null || readerView == null || !readerView.isBookOpen()) return null;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null || document.text() == null) return null;
        String bookId = currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        javafx.stage.Window owner = readerContainer != null && readerContainer.getScene() != null
                ? readerContainer.getScene().getWindow() : null;
        return new ReaderAnnotationCoordinator.Context(
                owner,
                requestToken.collectionId(),
                bookId,
                currentReaderArtifactId,
                document,
                knownAnnotationTags(),
                () -> sameOpenBook(requestToken, bookId),
                () -> {
                    if (readerView != null) readerView.clearTextSelection();
                },
                this::refreshAnnotationsAsync,
                text -> appState.getStatusBar().setStatusText(text),
                workspaceManager::showAnnotationManagerWorkspace,
                () -> { if (readerView != null) readerView.requestFocus(); });
    }

    private boolean annotationActionAvailable(ReaderSelection selection) {
        return selectionActionAvailable(selection);
    }

    private void jumpToContentSearchTarget() {
        Long targetOffset = contentTargetOffset;
        contentTargetOffset = null;
        contentTargetArtifactId = null;
        if (targetOffset == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null) return;
        long safeOffset = Math.max(0L, Math.min(targetOffset, Math.max(0L, document.totalTextLength() - 1L)));
        ReaderPosition position = new ReaderPosition(Math.max(0, document.chapterIndexAt(safeOffset)), safeOffset, 0, 0);
        readerView.goToPosition(position);
        positionChanged = true;
        if (positionAutosaver != null) positionAutosaver.mark(position);
    }

    private void refreshAnnotationsAsync() {
        if (isDisposed || readerView == null || !readerView.isBookOpen() || currentBookId == null
                || !currentBookBelongsToActiveCollection()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null) return;
        String bookId = currentBookId.asString();
        String artifactId = currentReaderArtifactId;
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        uiBackgroundExecutor.submitCollectionScoped(LibraryOperationType.QUERY,
                () -> ReaderAnnotationPresenter.presentation(
                        annotationService.listBookAnnotationViews(bookId), artifactId, document))
                .thenAccept(presentation -> Platform.runLater(() -> {
                    if (!sameOpenBook(requestToken, bookId)) return;
                    readerView.setAnnotationOverlays(presentation.overlays());
                    refreshAnnotationSidebar(presentation);
                    jumpToAnnotationTarget(document, presentation);
                }))
                .exceptionally(error -> {
                    log.warn("Не вдалося завантажити annotations для книги {}: {}", bookId, rootMessage(error));
                    return null;
                });
    }

    private void jumpToAnnotationTarget(ReaderDocument document, ReaderAnnotationPresentation presentation) {
        String target = annotationTargetId;
        if (target == null || document == null || presentation == null || readerView == null) return;
        var match = presentation.overlays().stream().filter(item -> target.equals(item.id())).findFirst().orElse(null);
        annotationTargetId = null;
        if (match == null) {
            ReaderAnnotationUnavailable unavailable = presentation.unavailable(target);
            String key = unavailable != null && unavailable.reason() == ReaderAnnotationUnavailableReason.ARTIFACT_MISMATCH
                    ? "ui.reader.annotation.artifact_mismatch"
                    : "ui.reader.annotation.unresolved";
            appState.getStatusBar().setStatusText(i18n.text(key));
            return;
        }
        long offset = match.startOffset();
        ReaderPosition position = new ReaderPosition(
                Math.max(0, document.chapterIndexAt(offset)), offset, 0, 0);
        readerView.goToPosition(position);
        positionChanged = true;
        if (positionAutosaver != null) positionAutosaver.mark(position);
    }

    /** Resolves the artifact whose file projection is actually opened by the current Reader. */
    private void addBookmark() {
        if (isDisposed || currentBookId == null || !currentBookBelongsToActiveCollection()) return;
        boolean pdfOpen = currentPdf && pdfReaderView != null && pdfReaderView.isOpen();
        boolean comicOpen = currentComic && comicReaderView != null && comicReaderView.isOpen();
        boolean audioOpen = currentAudio && audioReaderView != null && audioReaderView.isOpen();
        boolean textOpen = !currentPdf && !currentComic && !currentAudio && readerView != null && readerView.isBookOpen();
        if (!pdfOpen && !comicOpen && !audioOpen && !textOpen) return;

        TextInputDialog dialog = new TextInputDialog("");
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.reader.bookmark.add.title"));
        dialog.setHeaderText(i18n.text("ui.reader.bookmark.add.header"));
        dialog.setContentText(i18n.text("common.name.label"));
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        String title = result.get() == null || result.get().isBlank()
                ? i18n.text("ui.reader.bookmark.default_title") : result.get().trim();
        long totalLength = currentDocumentLength();
        String bookId = currentBookId.asString();
        ReaderBookmarkCoordinator.Context context = bookmarkContext(bookId);
        java.util.function.Consumer<ReaderBookmarkItem> saved = bookmark -> {
            dialogService.showInfo(i18n.text("common.success"), i18n.format("ui.reader.bookmark.add.success", title));
            if (!currentPdf && !currentComic && !currentAudio) refreshTextBookmarks(bookId);
            log.info("⭐ Закладку додано: {}", title);
        };
        java.util.function.Consumer<Throwable> failed = error -> dialogService.showError(
                i18n.text("ui.reader.bookmarks.title"),
                i18n.format("ui.reader.bookmark.save_error", rootMessage(error)));
        if (audioOpen) {
            bookmarkCoordinator.saveAudio(context, audioReaderView.currentPosition(), audioReaderView.progressPercent(),
                    title, saved, failed);
        } else {
            ReaderPosition pos = pdfOpen ? pdfReaderView.currentPosition()
                    : comicOpen ? comicReaderView.currentPosition() : readerView.getCurrentPosition();
            if (pos == null) return;
            bookmarkCoordinator.saveText(context, pos, totalLength, title, saved, failed);
        }
    }

    private void showBookmarks() {
        if (isDisposed || currentBookId == null || !currentBookBelongsToActiveCollection()) return;
        boolean pdfOpen = currentPdf && pdfReaderView != null && pdfReaderView.isOpen();
        boolean comicOpen = currentComic && comicReaderView != null && comicReaderView.isOpen();
        boolean audioOpen = currentAudio && audioReaderView != null && audioReaderView.isOpen();
        boolean textOpen = !currentPdf && !currentComic && !currentAudio && readerView != null && readerView.isBookOpen();
        if (!pdfOpen && !comicOpen && !audioOpen && !textOpen) return;
        String bookId = currentBookId.asString();
        if (textOpen) {
            showTextBookmarksSidebar(bookId);
            return;
        }
        ReaderBookmarkCoordinator.Context context = bookmarkContext(bookId);
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.bookmarks.loading"));
        bookmarkCoordinator.load(context,
                bookmarks -> showBookmarksDialog(bookmarks, context),
                error -> dialogService.showError(i18n.text("ui.reader.bookmarks.title"),
                        i18n.format("ui.reader.bookmarks.load_error", rootMessage(error))));
    }

    private void showTextBookmarksSidebar(String bookId) {
        loadTextBookmarks(bookId, true, true);
    }

    /** Refreshes text-reader bookmarks without forcing a sidebar tab switch when another tool is active. */
    private void refreshTextBookmarks(String bookId) {
        loadTextBookmarks(bookId, false, false);
    }

    private void loadTextBookmarks(String bookId, boolean selectBookmarksTab, boolean reportStatus) {
        ReaderBookmarkCoordinator.Context context = bookmarkContext(bookId);
        if (selectBookmarksTab) showReaderSidebar(bookmarkSidebarTab);
        if (reportStatus) appState.getStatusBar().setStatusText(i18n.text("ui.reader.bookmarks.loading"));
        bookmarkCoordinator.load(context, bookmarks -> {
            currentSidebarBookmarks = bookmarks == null ? List.of() : List.copyOf(bookmarks);
            if (bookmarkSidebarList != null) {
                bookmarkSidebarList.setItems(javafx.collections.FXCollections.observableArrayList(currentSidebarBookmarks));
            }
            if (reportStatus) {
                appState.getStatusBar().setStatusText(currentSidebarBookmarks.isEmpty()
                        ? i18n.text("ui.reader.bookmarks.empty")
                        : i18n.format("ui.reader.bookmarks.loaded_count", currentSidebarBookmarks.size()));
            }
            refreshBookMapSidebar();
        }, error -> {
            if (reportStatus) {
                dialogService.showError(i18n.text("ui.reader.bookmarks.title"),
                        i18n.format("ui.reader.bookmarks.load_error", rootMessage(error)));
            }
        });
    }

    @FXML
    public void goToSidebarBookmark() {
        ReaderBookmarkItem bookmark = bookmarkSidebarList == null ? null : bookmarkSidebarList.getSelectionModel().getSelectedItem();
        if (bookmark == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderPosition target = persistenceService.bookmarkToPosition(bookmark, currentDocumentLength());
        readerView.goToPosition(target);
        positionChanged = true;
        if (positionAutosaver != null) positionAutosaver.mark(target);
    }

    @FXML
    public void deleteSidebarBookmark() {
        ReaderBookmarkItem bookmark = bookmarkSidebarList == null ? null : bookmarkSidebarList.getSelectionModel().getSelectedItem();
        if (bookmark == null || currentBookId == null) return;
        if (!dialogService.showConfirmation(i18n.text("ui.reader.bookmark.title"),
                i18n.text("ui.reader.bookmark.action_prompt"),
                new BookmarkChoice(bookmark, i18n.text("ui.reader.bookmark.default_title")).toString())) return;
        String bookId = currentBookId.asString();
        ReaderBookmarkCoordinator.Context context = bookmarkContext(bookId);
        bookmarkCoordinator.deleteAndReload(context, bookmark.id(), bookmarks -> {
            currentSidebarBookmarks = bookmarks == null ? List.of() : List.copyOf(bookmarks);
            bookmarkSidebarList.setItems(javafx.collections.FXCollections.observableArrayList(currentSidebarBookmarks));
            refreshBookMapSidebar();
            appState.getStatusBar().setStatusText(i18n.text("ui.reader.bookmark.deleted"));
        }, error -> dialogService.showError(i18n.text("ui.reader.bookmarks.title"),
                i18n.format("ui.reader.bookmark.delete_error", rootMessage(error))));
    }

    private void showBookmarksDialog(List<ReaderBookmarkItem> bookmarks, ReaderBookmarkCoordinator.Context context) {
        if (bookmarks == null || bookmarks.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.bookmarks.title"), i18n.text("ui.reader.bookmarks.empty"));
            return;
        }
        List<BookmarkChoice> choices = bookmarks.stream().map(bookmark -> new BookmarkChoice(bookmark, i18n.text("ui.reader.bookmark.default_title"))).toList();
        ChoiceDialog<BookmarkChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.reader.bookmarks.title"));
        dialog.setHeaderText(i18n.text("ui.reader.bookmarks.select_header"));
        dialog.setContentText(i18n.text("ui.reader.bookmarks.label"));
        Optional<BookmarkChoice> selected = dialog.showAndWait();
        if (selected.isEmpty() || !context.current()) return;

        ReaderBookmarkItem bookmark = selected.get().bookmark();
        ButtonType goTo = new ButtonType(i18n.text("ui.reader.bookmark.go_to"));
        ButtonType delete = new ButtonType(i18n.text("common.delete"));
        Alert action = new Alert(Alert.AlertType.CONFIRMATION);
        DialogLocalization.prepare(action);
        action.setTitle(i18n.text("ui.reader.bookmark.title"));
        action.setHeaderText(selected.get().toString());
        action.setContentText(i18n.text("ui.reader.bookmark.action_prompt"));
        action.getButtonTypes().setAll(goTo, delete, ButtonType.CANCEL);
        Optional<ButtonType> actionResult = action.showAndWait();
        if (actionResult.filter(goTo::equals).isPresent()) {
            if (currentAudio && audioReaderView != null && audioReaderView.isOpen()) {
                persistenceService.audioBookmarkToPosition(bookmark).ifPresent(audioReaderView::goToPosition);
            } else {
                ReaderPosition target = persistenceService.bookmarkToPosition(bookmark, currentDocumentLength());
                if (currentPdf && pdfReaderView != null && pdfReaderView.isOpen()) pdfReaderView.goToPosition(target);
                else if (currentComic && comicReaderView != null && comicReaderView.isOpen()) comicReaderView.goToPosition(target);
                else if (readerView != null && readerView.isBookOpen()) readerView.goToPosition(target);
            }
            positionChanged = true;
        } else if (actionResult.filter(delete::equals).isPresent()) {
            bookmarkCoordinator.delete(context, bookmark.id(),
                    () -> dialogService.showInfo(i18n.text("ui.reader.bookmarks.title"),
                            i18n.text("ui.reader.bookmark.deleted")),
                    error -> dialogService.showError(i18n.text("ui.reader.bookmarks.title"),
                            i18n.format("ui.reader.bookmark.delete_error", rootMessage(error))));
        }
    }

    private ReaderBookmarkCoordinator.Context bookmarkContext(String bookId) {
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        return new ReaderBookmarkCoordinator.Context(
                currentBookCollectionId, bookId, () -> sameOpenBook(requestToken, bookId));
    }

    private boolean sameOpenBook(UiAsyncRequestToken requestToken, String bookId) {
        return !isDisposed
                && UiAsyncRequestGuard.isCurrent(requestToken, openGeneration, appState)
                && Objects.equals(currentBookCollectionId, requestToken.collectionId())
                && currentBookId != null
                && currentBookId.asString().equals(bookId);
    }

    private boolean currentBookBelongsToActiveCollection() {
        return Objects.equals(currentBookCollectionId, UiAsyncRequestGuard.currentCollectionId(appState));
    }

    private record BookmarkChoice(ReaderBookmarkItem bookmark, String defaultTitle) {
        @Override
        public String toString() {
            String title = bookmark.chapterTitle();
            if (title == null || title.isBlank()) title = defaultTitle;
            return String.format(Locale.ROOT, "%s — %.1f%%", title, bookmark.position());
        }
    }

    private void showToc() {
        if (currentComic) return;
        if (currentAudio) { showAudioToc(); return; }
        if (currentPdf) { showPdfToc(); return; }
        if (isDisposed || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null || document.toc() == null || document.toc().isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.toc.title"), i18n.text("ui.reader.toc.empty"));
            return;
        }
        currentSidebarToc = flattenToc(document.toc().entries());
        refreshTocSidebarList();
        showReaderSidebar(tocSidebarTab);
        if (tocSidebarSearch != null) tocSidebarSearch.requestFocus();
    }

    private void refreshTocSidebarList() {
        if (tocSidebarList == null) return;
        String query = tocSidebarSearch == null || tocSidebarSearch.getText() == null
                ? "" : tocSidebarSearch.getText().trim().toLowerCase(Locale.ROOT);
        List<TocEntry> filtered = currentSidebarToc.stream()
                .filter(entry -> query.isEmpty() || (entry.title() != null
                        && entry.title().toLowerCase(Locale.ROOT).contains(query)))
                .toList();
        tocSidebarList.setItems(javafx.collections.FXCollections.observableArrayList(filtered));
    }

    @FXML
    public void goToSidebarTocEntry() {
        TocEntry entry = tocSidebarList == null ? null : tocSidebarList.getSelectionModel().getSelectedItem();
        if (entry == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null) return;
        ReaderPosition pos = new ReaderPosition(Math.max(0, document.chapterIndexAt(entry.textOffset())),
                entry.textOffset(), 0, 0);
        readerView.goToPosition(pos);
        positionChanged = true;
        if (positionAutosaver != null) positionAutosaver.mark(pos);
    }

    private void showAudioToc() {
        if (isDisposed || audioReaderView == null || !audioReaderView.isOpen()) return;
        List<AudioChapterChoice> choices = new ArrayList<>();
        var tracks = audioReaderView.tracks();
        for (int t = 0; t < tracks.size(); t++) {
            var track = tracks.get(t);
            for (int c = 0; c < track.chapters().size(); c++) {
                var chapter = track.chapters().get(c);
                choices.add(new AudioChapterChoice(t, c, chapter.title(), chapter.startMillis()));
            }
        }
        if (choices.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.toc.title"), i18n.text("ui.reader.toc.empty"));
            return;
        }
        ChoiceDialog<AudioChapterChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.reader.toc.title"));
        dialog.setHeaderText(i18n.text("ui.reader.audio.chapter_select"));
        dialog.setContentText(i18n.text("ui.reader.audio.chapter"));
        dialog.showAndWait().ifPresent(choice -> {
            audioReaderView.goToPosition(new AudioPosition(choice.startMillis(), choice.trackIndex(), choice.chapterIndex()));
            positionChanged = true;
        });
    }

    private record AudioChapterChoice(int trackIndex, int chapterIndex, String title, long startMillis) {
        @Override public String toString() { return title; }
    }

    private void showPdfToc() {
        if (isDisposed || pdfReaderView == null || !pdfReaderView.isOpen()) return;
        List<PdfOutlineEntry> entries = pdfReaderView.outlineEntries();
        if (entries.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.toc.title"), i18n.text("ui.reader.toc.empty"));
            return;
        }
        List<PdfOutlineChoice> choices = entries.stream().map(PdfOutlineChoice::new).toList();
        ChoiceDialog<PdfOutlineChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.reader.toc.title"));
        dialog.setHeaderText(i18n.text("ui.reader.pdf.toc.select_header"));
        dialog.setContentText(i18n.text("ui.reader.pdf.toc.label"));
        dialog.showAndWait().ifPresent(choice -> {
            pdfReaderView.goToPage(choice.entry().pageIndex());
            positionChanged = true;
        });
    }

    private void showPdfSearch() {
        if (isDisposed || pdfReaderView == null || !pdfReaderView.isOpen() || currentBookId == null) return;
        TextInputDialog dialog = new TextInputDialog("");
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.reader.search.title"));
        dialog.setHeaderText(i18n.text("ui.reader.search.enter_text"));
        dialog.setContentText(i18n.text("ui.reader.pdf.search.query"));
        Optional<String> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() == null || answer.get().isBlank()) return;

        String query = answer.get().strip();
        String bookId = currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.search.searching"));
        try {
            pdfReaderView.searchTextAsync(query, 100, outcome -> {
                if (!sameOpenBook(requestToken, bookId)) return;
                showPdfSearchOutcome(outcome);
            }, error -> {
                if (!sameOpenBook(requestToken, bookId)) return;
                log.warn("PDF text search failed", error);
                dialogService.showError(i18n.text("ui.reader.search.title"),
                        i18n.format("ui.reader.search.error_detail", UiExceptionSupport.message(error)));
            });
        } catch (RejectedExecutionException busy) {
            dialogService.showWarning(i18n.text("ui.reader.search.title"), i18n.text("ui.reader.search.queue_busy"));
        }
    }

    private void showPdfSearchOutcome(PdfSearchOutcome outcome) {
        if (outcome == null || !outcome.textLayerDetected()) {
            dialogService.showInfo(i18n.text("ui.reader.search.title"), i18n.text("ui.reader.pdf.search.no_text_layer"));
            return;
        }
        if (outcome.results().isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.search.title"), i18n.text("ui.reader.search.nothing_found"));
            return;
        }
        List<PdfSearchChoice> choices = outcome.results().stream().map(PdfSearchChoice::new).toList();
        ChoiceDialog<PdfSearchChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        DialogLocalization.prepare(dialog);
        dialog.setTitle(i18n.text("ui.reader.search.title"));
        dialog.setHeaderText(i18n.format("ui.reader.search.matches_found", choices.size()));
        dialog.setContentText(i18n.text("ui.reader.pdf.search.result"));
        dialog.showAndWait().ifPresent(choice -> {
            pdfReaderView.goToPage(choice.result().pageIndex());
            positionChanged = true;
        });
        appState.getStatusBar().setStatusText(i18n.format("ui.reader.search.matches_found", choices.size()));
    }

    private record PdfOutlineChoice(PdfOutlineEntry entry) {
        @Override public String toString() {
            String indent = "  ".repeat(Math.min(8, entry.level()));
            return indent + entry.title() + " — " + (entry.pageIndex() + 1);
        }
    }

    private record PdfSearchChoice(PdfSearchResult result) {
        @Override public String toString() {
            return (result.pageIndex() + 1) + ": " + result.snippet();
        }
    }

    private void showSearch() {
        if (currentComic || currentAudio) return;
        if (currentPdf) { showPdfSearch(); return; }
        if (isDisposed || readerView == null || !readerView.isBookOpen()) return;
        showReaderSidebar(searchSidebarTab);
        if (readerSidebarSearchField != null) readerSidebarSearchField.requestFocus();
    }

    @FXML
    public void performSidebarSearch() {
        if (isDisposed || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        String query = readerSidebarSearchField == null ? "" : readerSidebarSearchField.getText();
        String bookId = currentBookId == null ? "" : currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        sidebarSearchCoordinator().search(
                document,
                query,
                () -> sameOpenBook(requestToken, bookId),
                results -> {
                    if (readerSidebarSearchResults != null) {
                        readerSidebarSearchResults.setItems(javafx.collections.FXCollections.observableArrayList(results));
                    }
                },
                status -> {
                    if (readerSidebarSearchStatus != null) readerSidebarSearchStatus.setText(status);
                });
    }

    @FXML
    public void goToSidebarSearchResult() {
        ReaderSearchService.SearchResult selected = readerSidebarSearchResults == null ? null
                : readerSidebarSearchResults.getSelectionModel().getSelectedItem();
        if (selected == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null) return;
        ReaderPosition pos = new ReaderPosition(Math.max(0, document.chapterIndexAt(selected.textOffset())),
                selected.textOffset(), selected.paragraphIndex(), 0);
        readerView.goToPosition(pos);
        positionChanged = true;
        if (positionAutosaver != null) positionAutosaver.mark(pos);
    }

    private ReaderSidebarSearchCoordinator sidebarSearchCoordinator() {
        if (sidebarSearchCoordinator == null) {
            sidebarSearchCoordinator = new ReaderSidebarSearchCoordinator(uiBackgroundExecutor, i18n);
        }
        return sidebarSearchCoordinator;
    }

    private void cancelReaderSidebarSearch() {
        ReaderSidebarSearchCoordinator coordinator = sidebarSearchCoordinator;
        if (coordinator != null) coordinator.cancel();
    }

    private void refreshBookMapSidebar() {
        if (bookMapSidebarList == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null || document.chapters() == null) return;
        List<ReaderPosition> bookmarkPositions = currentSidebarBookmarks.stream()
                .map(bookmark -> persistenceService.bookmarkToPosition(bookmark, document.totalTextLength()))
                .toList();
        List<ReaderBookMapCoordinator.BookMapRow> rows = bookMapCoordinator.buildRows(
                document, readerView.getCurrentPosition(), currentAnnotationPresentation, bookmarkPositions);
        bookMapSidebarList.setItems(javafx.collections.FXCollections.observableArrayList(rows));
    }

    @FXML
    public void goToBookMapRow() {
        ReaderBookMapCoordinator.BookMapRow row = bookMapSidebarList == null
                ? null : bookMapSidebarList.getSelectionModel().getSelectedItem();
        if (row == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderPosition pos = new ReaderPosition(row.chapterIndex(), row.startOffset(), 0, 0);
        readerView.goToPosition(pos);
        positionChanged = true;
        if (positionAutosaver != null) positionAutosaver.mark(pos);
        refreshBookMapSidebar();
    }

    private void showBookMap() {
        if (isDisposed || readerView == null || !readerView.isBookOpen() || currentPdf || currentComic || currentAudio) return;
        showReaderSidebar(bookMapSidebarTab);
        if (currentBookId != null) refreshTextBookmarks(currentBookId.asString());
        refreshBookMapSidebar();
    }

    @Override
    public void dispose() {
        if (isDisposed) {
            return;
        }
        isDisposed = true;
        openGeneration.incrementAndGet();
        cancelPendingOpen();
        cancelReaderSidebarSearch();
        annotationCoordinator.hidePopover();
        cancelTextProviderRequests();
        stopTts();

        log.info("🧹 NewReaderWorkspaceController: початок очищення");

        if (positionChanged) {
            savePosition();
            positionChanged = false;
        } else {
            savePosition();
        }

        boolean finalPositionSaved = true;
        if (positionAutosaver != null) {
            finalPositionSaved &= positionAutosaver.closeAndFlush();
            positionAutosaver = null;
        }
        if (audioPositionAutosaver != null) {
            finalPositionSaved &= audioPositionAutosaver.closeAndFlush();
            audioPositionAutosaver = null;
        }
        if (!finalPositionSaved) {
            appState.getStatusBar().setStatusText(i18n.text("ui.reader.warning.position_save_failed"));
        }

        finishReadingSession();
        if (readerView != null) {
            if (readerView.isBookOpen()) readerView.closeBook();
            readerView.dispose();
            readerView = null;
        }
        if (pdfReaderView != null) {
            pdfReaderView.close();
            pdfReaderView = null;
        }
        if (comicReaderView != null) {
            comicReaderView.close();
            comicReaderView = null;
        }
        if (audioReaderView != null) {
            audioReaderView.close();
            audioReaderView = null;
        }
        cleanupMaterializedBookFile();

        if (readerContainer != null) {
            Platform.runLater(() -> readerContainer.getChildren().clear());
        }

        persistenceService.clearCache();

        currentBook = null;
        currentBookId = null;
        currentBookCollectionId = null;

        log.info("🧹 NewReaderWorkspaceController знищено");
    }
}
