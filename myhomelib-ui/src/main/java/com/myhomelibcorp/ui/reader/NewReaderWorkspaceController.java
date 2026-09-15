package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.reader.AudiobookTrackGrouping;
import com.myhomelibcorp.application.annotation.AnnotationService;
import com.myhomelibcorp.application.dictionary.DictionaryEntry;
import com.myhomelibcorp.application.dictionary.DictionaryLookupResult;
import com.myhomelibcorp.application.dictionary.DictionaryLookupService;
import com.myhomelibcorp.application.dictionary.DictionaryProviderDescriptor;
import com.myhomelibcorp.application.dictionary.DictionaryQuery;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderIssue;
import com.myhomelibcorp.application.translation.TranslationLookupResult;
import com.myhomelibcorp.application.translation.TranslationProviderDescriptor;
import com.myhomelibcorp.application.translation.TranslationQuery;
import com.myhomelibcorp.application.translation.TranslationService;
import com.myhomelibcorp.application.tts.TtsPlayback;
import com.myhomelibcorp.application.tts.TtsPlaybackListener;
import com.myhomelibcorp.application.tts.TtsPlaybackRequest;
import com.myhomelibcorp.application.tts.TtsPlaybackService;
import com.myhomelibcorp.application.tts.TtsPlaybackState;
import com.myhomelibcorp.application.tts.TtsSentence;
import com.myhomelibcorp.application.tts.TtsVoice;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.reader.ReaderSettingsState;
import com.myhomelibcorp.application.reader.ReaderSettingsStateService;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.application.service.ReadingHistoryService;
import com.myhomelibcorp.application.service.ReadingSessionService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
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
import com.myhomelibcorp.reader.render.pdf.PdfReaderView;
import com.myhomelibcorp.reader.render.pdf.PdfSearchOutcome;
import com.myhomelibcorp.reader.render.pdf.PdfSearchResult;
import com.myhomelibcorp.reader.service.ReaderSearchService;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.MainLayoutService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.util.UiAsyncRequestGuard;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextArea;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private final DictionaryLookupService dictionaryLookupService;
    private final TranslationService translationService;
    private final TtsPlaybackService ttsPlaybackService;
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
    @FXML private ListView<Bookmark> bookmarkSidebarList;
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
    @FXML private ListView<BookMapRow> bookMapSidebarList;

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
    private final AtomicReference<AtomicBoolean> dictionaryCancellation = new AtomicReference<>();
    private final AtomicReference<AtomicBoolean> translationCancellation = new AtomicReference<>();
    private volatile TtsPlayback ttsPlayback;
    private long ttsGeneration;
    private String selectedTtsVoiceId;
    private double selectedTtsRate = 1.0;

    private ReaderPositionAutosaver positionAutosaver;
    private AudioPositionAutosaver audioPositionAutosaver;
    private boolean positionChanged = false;
    private boolean currentBookOverride = false;
    private String annotationTargetId;
    private ReaderAnnotationPresentation currentAnnotationPresentation =
            new ReaderAnnotationPresentation(List.of(), List.of());
    private List<TocEntry> currentSidebarToc = List.of();
    private List<Bookmark> currentSidebarBookmarks = List.of();
    private final AtomicLong readerSidebarSearchGeneration = new AtomicLong();
    private volatile Future<?> readerSidebarSearchTask;
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
                if (body.length() > 150) body = body.substring(0, 147) + "…";
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
                    setText(context.length() <= 160 ? context : context.substring(0, 157) + "…");
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
                @Override protected void updateItem(Bookmark item, boolean empty) {
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
                @Override protected void updateItem(BookMapRow item, boolean empty) {
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
        pdfReaderView.setOnError(message -> dialogService.showError(
                i18n.text("common.error"), i18n.format("ui.reader.pdf.render_failed", message == null ? "" : message)));

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
        comicReaderView.setOnError(message -> dialogService.showError(
                i18n.text("common.error"), i18n.format("ui.reader.comic.render_failed", message == null ? "" : message)));

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
        audioReaderView.setOnError(message -> dialogService.showError(
                i18n.text("common.error"), i18n.format("ui.reader.audio.playback_failed", message == null ? "" : message)));

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

        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.next(openGeneration, appState);
        cancelPendingOpen();
        closeCurrentBookForReplacement();
        setLoading(true);
        log.info("📖 Асинхронна підготовка книги: {}", bookId);

        try {
            ReaderEngine engine = readerView.getEngine();
            openTask = uiBackgroundExecutor.submitCancellable(() -> {
                try {
                    PreparedOpen prepared = prepareOpen(bookId, engine);
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
        }
    }

    /**
     * Показує прогрес завантаження книги.
     */
    private void showDownloadProgress(double progress, String status) {
        Platform.runLater(() -> {
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

    private PreparedOpen prepareOpen(BookId bookId, ReaderEngine engine) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Reader open cancelled");

        long openStarted = System.nanoTime();
        long resolveStarted = openStarted;
        showDownloadProgress(-1, i18n.text("ui.reader.progress.loading_metadata"));

        BookDto dto = loadBookByIdUseCase.execute(bookId)
                .orElseThrow(() -> new IOException(i18n.format("ui.reader.error.book_not_found", bookId)));
        Book projectedBook = bookMapper.toDomain(dto);
        String targetArtifact = contentTargetArtifactId;
        if (targetArtifact != null) {
            try {
                projectedBook = projectedBook.selectPreferredArtifact(targetArtifact);
            } catch (IllegalArgumentException mismatch) {
                log.warn("Content-search artifact {} is no longer available for book {}; opening preferred artifact", targetArtifact, bookId);
            }
        }
        final Book book = projectedBook;

        showDownloadProgress(0.1, i18n.text("ui.reader.progress.finding_file"));
        Path filePath = bookResourcePort.locateBookContainer(book)
                .orElseThrow(() -> new IOException(i18n.format("ui.reader.error.file_not_found", book.getFileName())));
        long resolveNanos = System.nanoTime() - resolveStarted;

        showDownloadProgress(0.3, i18n.text("ui.reader.progress.preparing_file"));
        long materializeStarted = System.nanoTime();
        MaterializedReaderSource materialized = materializeReaderEntryIfNeeded(book, filePath);
        long materializeNanos = System.nanoTime() - materializeStarted;
        PreparedBook preparedBook = null;
        PdfDocumentSession pdfDocument = null;
        ComicDocumentSession comicDocument = null;
        AudioDocumentSession audioDocument = null;
        try {
            ReaderSettingsState state = readerSettingsStateService.load(book.getId().asString());
            ReaderSettings settings = ReaderSettingsMapper.fromDomain(state.preferences());
            Optional<ReaderPosition> savedPosition = Optional.empty();
            Optional<AudioPosition> savedAudioPosition = Optional.empty();
            String persistenceWarning = null;
            BookSource source = new FileBookSource(materialized.readerPath(), book.getId().asString());
            boolean audio = isAudioExtension(source.extension());
            try {
                if (audio) savedAudioPosition = persistenceService.loadAudioPosition(book.getId().asString());
                else savedPosition = persistenceService.loadPosition(book.getId().asString());
            } catch (RuntimeException persistenceError) {
                log.warn("Не вдалося завантажити позицію читання для {}", book.getId(), persistenceError);
                persistenceWarning = i18n.text("ui.reader.warning.position_load_failed");
            }

            showDownloadProgress(0.6, i18n.text("ui.reader.progress.analyzing_structure"));
            long parseStarted = System.nanoTime();
            if (audio) {
                audioDocument = openAudioSession(book, materialized.readerPath());
            } else if ("pdf".equalsIgnoreCase(source.extension())) {
                pdfDocument = PdfDocumentSession.open(source);
            } else if (isComicExtension(source.extension())) {
                comicDocument = openComicSession(materialized.readerPath());
            } else {
                preparedBook = engine.prepare(source);
            }
            long parseNanos = System.nanoTime() - parseStarted;
            long bytes = Files.size(materialized.readerPath());
            ReaderOpenTiming timing = new ReaderOpenTiming(openStarted, resolveNanos, materializeNanos, parseNanos,
                    bytes, source.extension());

            showDownloadProgress(1.0, i18n.text("ui.reader.progress.ready"));
            return new PreparedOpen(dto, book, preparedBook, pdfDocument, comicDocument, audioDocument, materialized.temporaryPath(),
                    settings, state.bookOverride(), savedPosition, savedAudioPosition, persistenceWarning, timing);
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
            currentReaderArtifactId = resolveReaderArtifactId(prepared.book());
            // Reader is also a book workspace. Keep the shared right details panel bound to the
            // opened book, but do not fire the property again when navigation already selected
            // this same logical book. Re-emitting the same id restarts BookDetailsAnalysisService
            // and causes an avoidable second FB2/EPUB parse immediately after Reader open.
            appState.getBookDetails().setCurrentBookIfDifferentId(currentBook);
            materializedBookFile = prepared.temporaryPath();
            currentBookOverride = prepared.bookOverride();
            positionChanged = false;

            if (loadingIndicator != null) {
                loadingIndicator.setVisible(false);
                loadingIndicator.setManaged(false);
            }

            currentPdf = prepared.pdfDocument() != null;
            currentComic = prepared.comicDocument() != null;
            currentAudio = prepared.audioDocument() != null;
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
            if (currentAudio) audioPositionAutosaver.start(currentBookId.asString());
            else positionAutosaver.start(currentBookId.asString(), totalTextLength);
            readingSessionService.start(currentBookId.asString(), currentProgressPercent());
            setLoading(false);
            if (!currentPdf && !currentComic && !currentAudio) refreshAnnotationsAsync();
            if (prepared.persistenceWarning() != null) {
                appState.getStatusBar().setStatusText(prepared.persistenceWarning());
            }

            BookId openedId = currentBookId;
            uiBackgroundExecutor.execute(() -> {
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
        log.error("❌ Помилка відкриття книги {}", bookId, root);
        dialogService.showError(i18n.text("common.error"), i18n.format("ui.reader.error.open_failed", rootMessage(root)));
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

    private static boolean isComicExtension(String extension) {
        return "cbz".equalsIgnoreCase(extension) || "cbr".equalsIgnoreCase(extension);
    }

    private static boolean isAudioExtension(String extension) {
        return "mp3".equalsIgnoreCase(extension) || "m4b".equalsIgnoreCase(extension);
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

    private MaterializedReaderSource materializeReaderEntryIfNeeded(Book book, Path physicalPath) throws IOException {
        String selectedEntry = book.getArchiveEntry();
        boolean physicalArchive = bookResourcePort.isArchive(physicalPath.toString());
        if ((selectedEntry == null || selectedEntry.isBlank()) && !physicalArchive) {
            return new MaterializedReaderSource(physicalPath, null);
        }
        if ((selectedEntry == null || selectedEntry.isBlank()) && physicalArchive) {
            // Keep the complete archive. ZipParser will preserve every supported book
            // and build a hierarchical book -> chapter TOC instead of silently opening
            // only the first member.
            return new MaterializedReaderSource(physicalPath, null);
        }
        if (selectedEntry == null || selectedEntry.isBlank() || !isReaderEntry(selectedEntry)) {
            return new MaterializedReaderSource(physicalPath, null);
        }

        Path temp = Files.createTempFile("myhomelib-reader-book-", readerSuffix(selectedEntry));
        boolean success = false;
        try {
            Optional<String> actualEntry = bookResourcePort.materializeArchiveBookEntry(
                    book, physicalPath, temp, ArchiveSafetyLimits.MAX_ENTRY_BYTES,
                    () -> Thread.currentThread().isInterrupted());
            if (actualEntry.isEmpty()) {
                throw new IOException(i18n.format("ui.reader.error.archive_entry_read", selectedEntry));
            }
            success = true;
            return new MaterializedReaderSource(temp, temp);
        } finally {
            if (!success) Files.deleteIfExists(temp);
        }
    }

    private static void deleteTemp(Path temp) {
        if (temp == null) return;
        try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = unwrap(error);
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record MaterializedReaderSource(Path readerPath, Path temporaryPath) { }

    private record ReaderOpenTiming(long openStartedNanos, long resolveNanos, long materializeNanos,
                                    long parseNanos, long bytes, String format) { }

    private static double millis(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 10.0) / 10.0;
    }

    private record PreparedOpen(
            BookDto dto, Book book, PreparedBook preparedBook, PdfDocumentSession pdfDocument,
            ComicDocumentSession comicDocument, AudioDocumentSession audioDocument, Path temporaryPath,
            ReaderSettings settings, boolean bookOverride, Optional<ReaderPosition> savedPosition,
            Optional<AudioPosition> savedAudioPosition, String persistenceWarning, ReaderOpenTiming timing) {
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

    private boolean isReaderEntry(String name) {
        return SupportedFormatRegistry.standard().detect(name)
                .filter(format -> format.family() == SupportedFormat.Family.BOOK)
                .map(SupportedFormat::readerSupported)
                .orElse(false);
    }

    private String readerSuffix(String name) {
        if (name == null) return ".book";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot) : ".book";
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
                else saved = persistenceService.saveAudioPosition(currentBookId.asString(), pos, audioReaderView.progressPercent());
            } else {
                ReaderPosition pos = currentPdf && pdfOpen ? pdfReaderView.currentPosition()
                        : currentComic && comicOpen ? comicReaderView.currentPosition()
                        : readerView.getCurrentPosition();
                if (pos == null) return;
                if (positionAutosaver != null) { positionAutosaver.mark(pos); saved = positionAutosaver.flush(); }
                else saved = persistenceService.savePosition(currentBookId.asString(), pos, currentDocumentLength());
            }
            if (!saved) appState.getStatusBar().setStatusText(i18n.text("ui.reader.warning.position_save_retry"));
        } catch (Exception e) {
            log.warn("Не вдалося зберегти позицію: {}", e.getMessage());
        }
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
        readingSessionService.finish(currentBookId.asString(), currentProgressPercent());
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
        persistReaderSettings(settings, currentBookOverride);
    }

    private void persistReaderSettings(ReaderSettings settings, boolean perBook) {
        if (settings == null) return;
        String bookId = currentBookId != null ? currentBookId.asString() : null;
        try {
            if (perBook && bookId != null) {
                var previous = readerSettingsStateService.load(bookId).preferences();
                readerSettingsStateService.saveForBook(bookId, ReaderSettingsMapper.toDomain(settings, previous));
                currentBookOverride = true;
            } else {
                var previous = readerSettingsStateService.loadGlobal();
                readerSettingsStateService.saveGlobal(ReaderSettingsMapper.toDomain(settings, previous));
                if (bookId != null) readerSettingsStateService.clearBookOverride(bookId);
                currentBookOverride = false;
            }
        } catch (RuntimeException error) {
            log.error("Не вдалося зберегти налаштування Reader", error);
            appState.getStatusBar().setStatusText(i18n.text("ui.reader.warning.settings_save_failed"));
        }
    }

    private void showSettings(ReaderSettings settings) {
        if (isDisposed || readerView == null) {
            return;
        }
        javafx.stage.Window owner = readerContainer != null && readerContainer.getScene() != null
                ? readerContainer.getScene().getWindow()
                : null;

        ReaderSettingsDialog.show(owner, settings, currentBookOverride, readerView::applySettings, i18n)
                .ifPresent(result -> {
                    readerView.applySettings(result.settings());
                    persistReaderSettings(result.settings(), result.bookOverride());
                });
    }

    static String ttsVoiceLabel(TtsVoice voice) {
        if (voice == null) return "";
        String name = voice.displayName() == null || voice.displayName().isBlank() ? voice.id() : voice.displayName();
        String language = voice.languageTag() == null ? "" : voice.languageTag().trim();
        return language.isBlank() ? name : name + " (" + language + ")";
    }

    static List<String> ttsVoiceLabels(List<TtsVoice> voices) {
        List<String> labels = new ArrayList<>();
        if (voices == null) return labels;
        for (TtsVoice voice : voices) {
            String base = ttsVoiceLabel(voice);
            String label = base;
            int suffix = 2;
            while (labels.contains(label)) label = base + " [" + suffix++ + "]";
            labels.add(label);
        }
        return List.copyOf(labels);
    }

    private void startTts() {
        if (isDisposed || currentPdf || currentComic || currentAudio || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        ReaderPosition position = readerView.getCurrentPosition();
        if (document == null || position == null) return;

        List<TtsVoice> voices = ttsPlaybackService.availableVoices();
        if (voices.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.tts.title"), i18n.text("ui.reader.tts.unavailable"));
            return;
        }
        String language = document.metadata().language();
        TtsVoice suggested = voices.stream()
                .filter(v -> selectedTtsVoiceId != null && selectedTtsVoiceId.equals(v.id()))
                .findFirst()
                .orElseGet(() -> voices.stream()
                        .filter(v -> language != null && !language.isBlank()
                                && v.languageTag().toLowerCase(Locale.ROOT).startsWith(language.toLowerCase(Locale.ROOT)))
                        .findFirst().orElse(voices.getFirst()));
        List<String> voiceLabels = ttsVoiceLabels(voices);
        int suggestedIndex = Math.max(0, voices.indexOf(suggested));
        String suggestedLabel = voiceLabels.get(suggestedIndex);
        ChoiceDialog<String> voiceDialog = new ChoiceDialog<>(suggestedLabel, voiceLabels);
        voiceDialog.setTitle(i18n.text("ui.reader.tts.title"));
        voiceDialog.setHeaderText(i18n.text("ui.reader.tts.voice_header"));
        voiceDialog.setContentText(i18n.text("ui.reader.tts.voice_label"));
        Optional<String> selectedVoiceLabel = voiceDialog.showAndWait();
        if (selectedVoiceLabel.isEmpty()) return;
        int selectedVoiceIndex = voiceLabels.indexOf(selectedVoiceLabel.get());
        if (selectedVoiceIndex < 0 || selectedVoiceIndex >= voices.size()) return;
        TtsVoice selectedVoice = voices.get(selectedVoiceIndex);

        TextInputDialog rateDialog = new TextInputDialog(String.format(Locale.ROOT, "%.2f", selectedTtsRate));
        rateDialog.setTitle(i18n.text("ui.reader.tts.title"));
        rateDialog.setHeaderText(i18n.text("ui.reader.tts.speed_header"));
        rateDialog.setContentText(i18n.text("ui.reader.tts.speed_label"));
        Optional<String> selectedRate = rateDialog.showAndWait();
        if (selectedRate.isEmpty()) return;
        double rate;
        try { rate = Double.parseDouble(selectedRate.get().trim().replace(',', '.')); }
        catch (NumberFormatException invalid) {
            dialogService.showError(i18n.text("common.error"), i18n.text("ui.reader.tts.speed_invalid"));
            return;
        }
        if (rate < 0.5 || rate > 2.0) {
            dialogService.showError(i18n.text("common.error"), i18n.text("ui.reader.tts.speed_invalid"));
            return;
        }

        long total = document.totalTextLength();
        int start = (int) Math.max(0L, Math.min((long) Integer.MAX_VALUE, Math.min(position.textOffset(), total)));
        int end = (int) Math.max(start, Math.min((long) Integer.MAX_VALUE, total));
        String readableText = document.text().getText(start, end);
        if (readableText.isBlank()) return;

        stopTts();
        long ttsToken = ++ttsGeneration;
        selectedTtsVoiceId = selectedVoice.id();
        selectedTtsRate = rate;
        TtsPlaybackRequest request = new TtsPlaybackRequest(readableText, start, language, selectedTtsVoiceId, rate);
        try {
            ttsPlayback = ttsPlaybackService.start(request, new TtsPlaybackListener() {
                @Override public void onStateChanged(TtsPlaybackState state) {
                    Platform.runLater(() -> { if (ttsToken == ttsGeneration) applyTtsState(state); });
                }
                @Override public void onSentenceStarted(TtsSentence sentence) {
                    Platform.runLater(() -> { if (ttsToken == ttsGeneration) showTtsSentence(document, sentence); });
                }
                @Override public void onFailed(Throwable error) {
                    Platform.runLater(() -> {
                        if (ttsToken == ttsGeneration) {
                            dialogService.showError(i18n.text("ui.reader.tts.title"),
                                    i18n.format("ui.reader.tts.failed", rootMessage(error)));
                        }
                    });
                }
            });
            readerView.updateTtsState(true, false);
        } catch (RuntimeException error) {
            ttsPlayback = null;
            readerView.updateTtsState(false, false);
            dialogService.showError(i18n.text("ui.reader.tts.title"), i18n.format("ui.reader.tts.failed", rootMessage(error)));
        }
    }

    private void showTtsSentence(ReaderDocument document, TtsSentence sentence) {
        if (isDisposed || currentPdf || currentComic || currentAudio || readerView == null || !readerView.isBookOpen()) return;
        long offset = Math.max(0L, Math.min(sentence.startOffset(), document.totalTextLength()));
        ReaderPosition target = new ReaderPosition(Math.max(0, document.chapterIndexAt(offset)), offset, 0, 0);
        readerView.goToPosition(target);
        readerView.setSpeechHighlight(sentence.startOffset(), sentence.endOffset());
    }

    private void pauseResumeTts() {
        TtsPlayback playback = ttsPlayback;
        if (playback == null) return;
        if (playback.state() == TtsPlaybackState.PAUSED) playback.resume();
        else if (playback.state() == TtsPlaybackState.PLAYING) playback.pause();
    }

    private void stopTts() {
        ttsGeneration++;
        TtsPlayback playback = ttsPlayback;
        ttsPlayback = null;
        if (playback != null) playback.stop();
        if (readerView != null) {
            readerView.clearSpeechHighlight();
            readerView.updateTtsState(false, false);
        }
    }

    private void applyTtsState(TtsPlaybackState state) {
        if (readerView == null) return;
        boolean active = state == TtsPlaybackState.PLAYING || state == TtsPlaybackState.PAUSED;
        readerView.updateTtsState(active, state == TtsPlaybackState.PAUSED);
        if (!active) {
            readerView.clearSpeechHighlight();
            if (ttsPlayback != null && ttsPlayback.state() == state) ttsPlayback = null;
        }
    }

    private void lookupDictionaryFromSelection(ReaderSelection selection) {
        if (!selectionActionAvailable(selection)) return;
        String term = selection.text() == null ? "" : selection.text().strip();
        if (term.isBlank()) return;
        if (term.length() > DictionaryQuery.MAX_TERM_LENGTH) {
            dialogService.showWarning(i18n.text("ui.reader.dictionary.title"),
                    i18n.format("ui.reader.dictionary.selection_too_long", DictionaryQuery.MAX_TERM_LENGTH));
            return;
        }

        List<DictionaryProviderDescriptor> providers = dictionaryLookupService.availableProviders();
        if (providers.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.dictionary.title"),
                    i18n.text("ui.reader.text_provider.none.dictionary"));
            return;
        }
        List<DictionaryProviderChoice> choices = providers.stream().map(DictionaryProviderChoice::new).toList();
        Optional<DictionaryProviderChoice> selected = dialogService.showChoiceDialog(
                choices, choices.getFirst(), i18n.text("ui.reader.dictionary.title"),
                i18n.text("ui.reader.dictionary.provider.header"), i18n.text("ui.reader.dictionary.provider"));
        if (selected.isEmpty()) return;

        AtomicBoolean cancellation = replaceCancellation(dictionaryCancellation);
        String bookId = currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        String language = currentBook == null || currentBook.getLanguage() == null ? "" : currentBook.getLanguage();
        DictionaryQuery query = new DictionaryQuery(term, language, DictionaryQuery.DEFAULT_LIMIT);
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.dictionary.lookup"));
        dictionaryLookupService.lookup(query, selected.get().descriptor().id(), DictionaryLookupService.DEFAULT_TIMEOUT, cancellation)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (!dictionaryCancellation.compareAndSet(cancellation, null)) return;
                    if (!sameOpenBook(requestToken, bookId)) return;
                    showDictionaryResult(result, term);
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        if (!dictionaryCancellation.compareAndSet(cancellation, null)) return;
                        if (sameOpenBook(requestToken, bookId)) {
                            log.warn("Dictionary lookup failed: {}", rootMessage(error));
                            dialogService.showError(i18n.text("ui.reader.dictionary.title"),
                                    i18n.format("ui.reader.text_provider.issue.failed",
                                            selected.get().descriptor().displayName()));
                        }
                    });
                    return null;
                });
    }

    private void showDictionaryResult(DictionaryLookupResult result, String term) {
        if (result == null) {
            dialogService.showError(i18n.text("ui.reader.dictionary.title"),
                    i18n.text("ui.reader.text_provider.issue.failed"));
            return;
        }
        if (result.cancelled()) return;
        if (result.issue() != null) {
            if (result.issue().kind() == TextProviderErrorKind.NOT_FOUND) {
                dialogService.showInfo(i18n.text("ui.reader.dictionary.title"),
                        i18n.format("ui.reader.dictionary.empty", term));
            } else {
                showTextProviderIssue(i18n.text("ui.reader.dictionary.title"), result.issue());
            }
            return;
        }
        if (result.entries().isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.dictionary.title"),
                    i18n.format("ui.reader.dictionary.empty", term));
            return;
        }
        StringBuilder body = new StringBuilder();
        for (DictionaryEntry entry : result.entries()) {
            if (!body.isEmpty()) body.append("\n\n");
            body.append(entry.headword());
            if (!entry.partOfSpeech().isBlank()) body.append(" — ").append(entry.partOfSpeech());
            body.append("\n").append(entry.definition());
            for (String example : entry.examples()) body.append("\n• ").append(example);
            body.append("\n").append(i18n.format("ui.reader.dictionary.source", entry.providerName()));
        }
        showReadOnlyTextDialog(i18n.text("ui.reader.dictionary.title"), term, body.toString());
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.dictionary.ready"));
    }

    private void translateSelection(ReaderSelection selection) {
        if (!selectionActionAvailable(selection)) return;
        String text = selection.text() == null ? "" : selection.text().strip();
        if (text.isBlank()) return;
        if (text.length() > TranslationQuery.MAX_TEXT_LENGTH) {
            dialogService.showWarning(i18n.text("ui.reader.translation.title"),
                    i18n.format("ui.reader.translation.selection_too_long", TranslationQuery.MAX_TEXT_LENGTH));
            return;
        }

        List<TranslationProviderDescriptor> providers = translationService.availableProviders();
        if (providers.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.translation.title"),
                    i18n.text("ui.reader.text_provider.none.translation"));
            return;
        }
        List<TranslationProviderChoice> providerChoices = providers.stream().map(TranslationProviderChoice::new).toList();
        Optional<TranslationProviderChoice> selectedProvider = dialogService.showChoiceDialog(
                providerChoices, providerChoices.getFirst(), i18n.text("ui.reader.translation.title"),
                i18n.text("ui.reader.translation.provider.header"), i18n.text("ui.reader.translation.provider"));
        if (selectedProvider.isEmpty()) return;

        TranslationProviderDescriptor provider = selectedProvider.get().descriptor();
        List<TranslationLanguageChoice> languages = translationLanguages();
        String sourceLanguage = currentBook == null ? "" : normalizeOptionalLanguageCode(currentBook.getLanguage());
        String preferredTarget = normalizeLanguageCode(i18n.language());
        if (!sourceLanguage.isBlank() && sourceLanguage.equals(preferredTarget)) {
            preferredTarget = "en".equals(sourceLanguage) ? "uk" : "en";
        }
        String defaultTarget = preferredTarget;
        TranslationLanguageChoice defaultLanguage = languages.stream()
                .filter(choice -> choice.code().equals(defaultTarget))
                .findFirst().orElse(languages.getFirst());
        Optional<TranslationLanguageChoice> target = dialogService.showChoiceDialog(
                languages, defaultLanguage, i18n.text("ui.reader.translation.title"),
                i18n.text("ui.reader.translation.language.header"), i18n.text("ui.reader.translation.target_language"));
        if (target.isEmpty()) return;

        if (provider.remote() && !dialogService.showConfirmation(
                i18n.text("ui.reader.translation.privacy.title"),
                i18n.text("ui.reader.translation.privacy.header"),
                i18n.format("ui.reader.translation.privacy.message", provider.displayName()))) {
            return;
        }

        AtomicBoolean cancellation = replaceCancellation(translationCancellation);
        String bookId = currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        TranslationQuery query = sourceLanguage.isBlank() || sourceLanguage.equals(target.get().code())
                ? TranslationQuery.autoDetect(text, target.get().code())
                : new TranslationQuery(text, sourceLanguage, target.get().code());
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.translation.translating"));
        translationService.translate(query, provider.id(), TranslationService.DEFAULT_TIMEOUT, cancellation)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (!translationCancellation.compareAndSet(cancellation, null)) return;
                    if (!sameOpenBook(requestToken, bookId)) return;
                    showTranslationResult(result, text, provider.displayName());
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        if (!translationCancellation.compareAndSet(cancellation, null)) return;
                        if (sameOpenBook(requestToken, bookId)) {
                            log.warn("Translation failed: {}", rootMessage(error));
                            dialogService.showError(i18n.text("ui.reader.translation.title"),
                                    i18n.format("ui.reader.text_provider.issue.failed", provider.displayName()));
                        }
                    });
                    return null;
                });
    }

    private void showTranslationResult(TranslationLookupResult result, String sourceText, String providerName) {
        if (result == null) {
            dialogService.showError(i18n.text("ui.reader.translation.title"),
                    i18n.text("ui.reader.text_provider.issue.failed"));
            return;
        }
        if (result.cancelled()) return;
        if (result.issue() != null) {
            if (result.issue().kind() == TextProviderErrorKind.NOT_FOUND) {
                dialogService.showInfo(i18n.text("ui.reader.translation.title"),
                        i18n.text("ui.reader.translation.empty"));
            } else {
                showTextProviderIssue(i18n.text("ui.reader.translation.title"), result.issue());
            }
            return;
        }
        if (result.translation() == null) {
            dialogService.showInfo(i18n.text("ui.reader.translation.title"), i18n.text("ui.reader.translation.empty"));
            return;
        }
        String body = result.translation().translatedText()
                + "\n\n" + i18n.format("ui.reader.translation.source", providerName);
        showReadOnlyTextDialog(i18n.text("ui.reader.translation.title"), sourceText, body);
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.translation.ready"));
    }

    private void showTextProviderIssue(String title, TextProviderIssue issue) {
        if (issue == null || issue.kind() == TextProviderErrorKind.CANCELLED) return;
        String key = switch (issue.kind()) {
            case TIMEOUT -> "ui.reader.text_provider.issue.timeout";
            case RATE_LIMITED -> "ui.reader.text_provider.issue.rate_limited";
            case UNAVAILABLE -> "ui.reader.text_provider.issue.unavailable";
            case AUTHENTICATION -> "ui.reader.text_provider.issue.authentication";
            case NOT_FOUND -> "ui.reader.text_provider.issue.not_found";
            case INVALID_RESPONSE -> "ui.reader.text_provider.issue.invalid_response";
            case FAILED -> "ui.reader.text_provider.issue.failed";
            case CANCELLED -> "ui.reader.text_provider.issue.cancelled";
        };
        dialogService.showError(title, i18n.format(key, issue.providerName()));
    }

    private void showReadOnlyTextDialog(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        TextArea area = new TextArea(content == null ? "" : content);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(64);
        area.setPrefRowCount(18);
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
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

    private static AtomicBoolean replaceCancellation(AtomicReference<AtomicBoolean> ref) {
        AtomicBoolean next = new AtomicBoolean(false);
        AtomicBoolean previous = ref.getAndSet(next);
        if (previous != null) previous.set(true);
        return next;
    }

    private void cancelTextProviderRequests() {
        cancelRequest(dictionaryCancellation);
        cancelRequest(translationCancellation);
    }

    private static void cancelRequest(AtomicReference<AtomicBoolean> ref) {
        AtomicBoolean active = ref.getAndSet(null);
        if (active != null) active.set(true);
    }

    private List<TranslationLanguageChoice> translationLanguages() {
        return List.of(
                languageChoice("uk"), languageChoice("en"), languageChoice("bg"), languageChoice("de"),
                languageChoice("fr"), languageChoice("es"), languageChoice("pl"), languageChoice("cs"),
                languageChoice("it"), languageChoice("pt"));
    }

    private TranslationLanguageChoice languageChoice(String code) {
        return new TranslationLanguageChoice(code, i18n.text("ui.reader.translation.language." + code));
    }

    private static String normalizeLanguageCode(String value) {
        String normalized = normalizeOptionalLanguageCode(value);
        return normalized.isBlank() ? "uk" : normalized;
    }

    private static String normalizeOptionalLanguageCode(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        int dash = normalized.indexOf('-');
        return dash < 0 ? normalized : normalized.substring(0, dash);
    }

    private record DictionaryProviderChoice(DictionaryProviderDescriptor descriptor) {
        @Override public String toString() {
            return descriptor.displayName();
        }
    }

    private record TranslationProviderChoice(TranslationProviderDescriptor descriptor) {
        @Override public String toString() {
            return descriptor.displayName();
        }
    }

    private record TranslationLanguageChoice(String code, String label) {
        @Override public String toString() { return label; }
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
        if (text.length() > 90) text = text.substring(0, 87) + "…";
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
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        currentAnnotationPresentation.overlays().stream()
                .flatMap(item -> item.tags().stream())
                .filter(tag -> tag != null && !tag.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(tags::add);
        List<AnnotationTagChoice> choices = new ArrayList<>();
        choices.add(new AnnotationTagChoice(null, i18n.text("ui.reader.annotation.sidebar.all_tags")));
        tags.forEach(tag -> choices.add(new AnnotationTagChoice(tag, "#" + tag)));
        annotationSidebarTagFilter.getItems().setAll(choices);
        choices.stream().filter(choice -> Objects.equals(choice.tag(), selected)).findFirst()
                .ifPresentOrElse(annotationSidebarTagFilter::setValue,
                        () -> annotationSidebarTagFilter.getSelectionModel().selectFirst());
    }

    private void refreshAnnotationSidebarList() {
        if (annotationSidebarList == null) return;
        String query = annotationSidebarSearch == null || annotationSidebarSearch.getText() == null
                ? "" : annotationSidebarSearch.getText().trim().toLowerCase(Locale.ROOT);
        ReaderAnnotationType type = annotationSidebarTypeFilter == null || annotationSidebarTypeFilter.getValue() == null
                ? null : annotationSidebarTypeFilter.getValue().type();
        String tag = annotationSidebarTagFilter == null || annotationSidebarTagFilter.getValue() == null
                ? null : annotationSidebarTagFilter.getValue().tag();
        List<ReaderAnnotationOverlay> items = currentAnnotationPresentation.overlays().stream()
                .filter(item -> type == null || item.type() == type)
                .filter(item -> tag == null || item.tags().contains(tag))
                .filter(item -> query.isEmpty() || annotationMatches(item, query))
                .sorted(java.util.Comparator.comparingLong(ReaderAnnotationOverlay::startOffset)
                        .thenComparing(ReaderAnnotationOverlay::updatedAt))
                .toList();
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
        if (document != null && chapterIndex >= 0 && chapterIndex < document.chapters().size()) {
            String title = document.chapters().get(chapterIndex).title();
            if (title != null && !title.isBlank()) return title;
        }
        if (item != null && item.chapterTitle() != null && !item.chapterTitle().isBlank()) return item.chapterTitle();
        return i18n.text("ui.annotations.digest_no_chapter");
    }

    private static boolean annotationMatches(ReaderAnnotationOverlay item, String query) {
        if (item == null || query == null || query.isBlank()) return true;
        if (item.noteText().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (item.quote().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (item.chapterTitle().toLowerCase(Locale.ROOT).contains(query)) return true;
        return item.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(query));
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
        uiBackgroundExecutor.submit(() -> ReaderAnnotationPresenter.presentation(
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
    private static String resolveReaderArtifactId(Book book) {
        if (book == null || book.getArtifacts().isEmpty()) return null;
        BookFile opened = book.getFile();
        return book.getArtifacts().stream()
                .filter(artifact -> sameFileIdentity(opened, artifact))
                .map(BookArtifact::getId)
                .findFirst()
                .orElse(null);
    }

    private static boolean sameFileIdentity(BookFile opened, BookArtifact artifact) {
        if (opened == null || artifact == null || artifact.getFile() == null) return false;
        BookFile candidate = artifact.getFile();
        return Objects.equals(normalizedPathPart(opened.getFileName()), normalizedPathPart(candidate.getFileName()))
                && Objects.equals(normalizedPathPart(opened.getFolder()), normalizedPathPart(candidate.getFolder()))
                && Objects.equals(normalizedPathPart(opened.getCollectionRoot()), normalizedPathPart(candidate.getCollectionRoot()))
                && Objects.equals(normalizedPathPart(opened.getArchiveEntry()), normalizedPathPart(candidate.getArchiveEntry()));
    }

    private static String normalizedPathPart(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    private void addBookmark() {
        if (isDisposed || currentBookId == null || !currentBookBelongsToActiveCollection()) return;
        boolean pdfOpen = currentPdf && pdfReaderView != null && pdfReaderView.isOpen();
        boolean comicOpen = currentComic && comicReaderView != null && comicReaderView.isOpen();
        boolean audioOpen = currentAudio && audioReaderView != null && audioReaderView.isOpen();
        boolean textOpen = !currentPdf && !currentComic && !currentAudio && readerView != null && readerView.isBookOpen();
        if (!pdfOpen && !comicOpen && !audioOpen && !textOpen) return;

        TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle(i18n.text("ui.reader.bookmark.add.title"));
        dialog.setHeaderText(i18n.text("ui.reader.bookmark.add.header"));
        dialog.setContentText(i18n.text("common.name.label"));
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        String title = result.get() == null || result.get().isBlank()
                ? i18n.text("ui.reader.bookmark.default_title") : result.get().trim();
        long totalLength = currentDocumentLength();
        String bookId = currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);

        java.util.concurrent.CompletableFuture<Bookmark> saveFuture;
        if (audioOpen) {
            AudioPosition audioPosition = audioReaderView.currentPosition();
            double percent = audioReaderView.progressPercent();
            saveFuture = uiBackgroundExecutor.submit(() -> persistenceService.saveAudioBookmark(bookId, audioPosition, percent, title, ""));
        } else {
            ReaderPosition pos = pdfOpen ? pdfReaderView.currentPosition()
                    : comicOpen ? comicReaderView.currentPosition() : readerView.getCurrentPosition();
            if (pos == null) return;
            saveFuture = uiBackgroundExecutor.submit(() -> persistenceService.saveBookmark(bookId, pos, totalLength, title, ""));
        }
        saveFuture.thenAccept(bookmark -> Platform.runLater(() -> {
                    if (!sameOpenBook(requestToken, bookId)) return;
                    dialogService.showInfo(i18n.text("common.success"), i18n.format("ui.reader.bookmark.add.success", title));
                    if (!currentPdf && !currentComic && !currentAudio) refreshTextBookmarks(bookId);
                    log.info("⭐ Закладку додано: {}", title);
                }))
                .exceptionally(error -> {
                    log.error("Не вдалося зберегти закладку", error);
                    Platform.runLater(() -> {
                        if (sameOpenBook(requestToken, bookId))
                            dialogService.showError(i18n.text("ui.reader.bookmarks.title"),
                                    i18n.format("ui.reader.bookmark.save_error", rootMessage(error)));
                    });
                    return null;
                });
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
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        appState.getStatusBar().setStatusText(i18n.text("ui.reader.bookmarks.loading"));
        uiBackgroundExecutor.submit(() -> persistenceService.loadBookmarks(bookId))
                .thenAccept(bookmarks -> Platform.runLater(() -> {
                    if (!sameOpenBook(requestToken, bookId)) return;
                    showBookmarksDialog(bookmarks, requestToken, bookId);
                }))
                .exceptionally(error -> {
                    log.error("Не вдалося завантажити закладки", error);
                    Platform.runLater(() -> {
                        if (sameOpenBook(requestToken, bookId))
                            dialogService.showError(i18n.text("ui.reader.bookmarks.title"), i18n.format("ui.reader.bookmarks.load_error", rootMessage(error)));
                    });
                    return null;
                });
    }

    private void showTextBookmarksSidebar(String bookId) {
        loadTextBookmarks(bookId, true, true);
    }

    /** Refreshes text-reader bookmarks without forcing a sidebar tab switch when another tool is active. */
    private void refreshTextBookmarks(String bookId) {
        loadTextBookmarks(bookId, false, false);
    }

    private void loadTextBookmarks(String bookId, boolean selectBookmarksTab, boolean reportStatus) {
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        if (selectBookmarksTab) showReaderSidebar(bookmarkSidebarTab);
        if (reportStatus) appState.getStatusBar().setStatusText(i18n.text("ui.reader.bookmarks.loading"));
        uiBackgroundExecutor.submit(() -> persistenceService.loadBookmarks(bookId))
                .thenAccept(bookmarks -> Platform.runLater(() -> {
                    if (!sameOpenBook(requestToken, bookId)) return;
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
                }))
                .exceptionally(error -> {
                    log.error("Не вдалося завантажити закладки", error);
                    Platform.runLater(() -> {
                        if (sameOpenBook(requestToken, bookId) && reportStatus) {
                            dialogService.showError(i18n.text("ui.reader.bookmarks.title"),
                                    i18n.format("ui.reader.bookmarks.load_error", rootMessage(error)));
                        }
                    });
                    return null;
                });
    }

    @FXML
    public void goToSidebarBookmark() {
        Bookmark bookmark = bookmarkSidebarList == null ? null : bookmarkSidebarList.getSelectionModel().getSelectedItem();
        if (bookmark == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderPosition target = persistenceService.bookmarkToPosition(bookmark, currentDocumentLength());
        readerView.goToPosition(target);
        positionChanged = true;
        if (positionAutosaver != null) positionAutosaver.mark(target);
    }

    @FXML
    public void deleteSidebarBookmark() {
        Bookmark bookmark = bookmarkSidebarList == null ? null : bookmarkSidebarList.getSelectionModel().getSelectedItem();
        if (bookmark == null || currentBookId == null) return;
        if (!dialogService.showConfirmation(i18n.text("ui.reader.bookmark.title"),
                i18n.text("ui.reader.bookmark.action_prompt"),
                new BookmarkChoice(bookmark, i18n.text("ui.reader.bookmark.default_title")).toString())) return;
        String bookId = currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        uiBackgroundExecutor.submit(() -> {
            persistenceService.deleteBookmark(bookmark.getId());
            return persistenceService.loadBookmarks(bookId);
        }).thenAccept(bookmarks -> Platform.runLater(() -> {
            if (!sameOpenBook(requestToken, bookId)) return;
            currentSidebarBookmarks = bookmarks == null ? List.of() : List.copyOf(bookmarks);
            bookmarkSidebarList.setItems(javafx.collections.FXCollections.observableArrayList(currentSidebarBookmarks));
            refreshBookMapSidebar();
            appState.getStatusBar().setStatusText(i18n.text("ui.reader.bookmark.deleted"));
        })).exceptionally(error -> {
            log.error("Не вдалося видалити закладку", error);
            Platform.runLater(() -> {
                if (sameOpenBook(requestToken, bookId)) {
                    dialogService.showError(i18n.text("ui.reader.bookmarks.title"),
                            i18n.format("ui.reader.bookmark.delete_error", rootMessage(error)));
                }
            });
            return null;
        });
    }

    private void showBookmarksDialog(List<Bookmark> bookmarks, UiAsyncRequestToken requestToken, String bookId) {
        if (bookmarks == null || bookmarks.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.bookmarks.title"), i18n.text("ui.reader.bookmarks.empty"));
            return;
        }
        List<BookmarkChoice> choices = bookmarks.stream().map(bookmark -> new BookmarkChoice(bookmark, i18n.text("ui.reader.bookmark.default_title"))).toList();
        ChoiceDialog<BookmarkChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        dialog.setTitle(i18n.text("ui.reader.bookmarks.title"));
        dialog.setHeaderText(i18n.text("ui.reader.bookmarks.select_header"));
        dialog.setContentText(i18n.text("ui.reader.bookmarks.label"));
        Optional<BookmarkChoice> selected = dialog.showAndWait();
        if (selected.isEmpty() || !sameOpenBook(requestToken, bookId)) return;

        Bookmark bookmark = selected.get().bookmark();
        ButtonType goTo = new ButtonType(i18n.text("ui.reader.bookmark.go_to"));
        ButtonType delete = new ButtonType(i18n.text("common.delete"));
        Alert action = new Alert(Alert.AlertType.CONFIRMATION);
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
            uiBackgroundExecutor.submit(() -> {
                persistenceService.deleteBookmark(bookmark.getId());
                return true;
            }).thenAccept(ignored -> Platform.runLater(() -> {
                if (sameOpenBook(requestToken, bookId)) dialogService.showInfo(i18n.text("ui.reader.bookmarks.title"), i18n.text("ui.reader.bookmark.deleted"));
            })).exceptionally(error -> {
                log.error("Не вдалося видалити закладку", error);
                Platform.runLater(() -> {
                    if (sameOpenBook(requestToken, bookId))
                        dialogService.showError(i18n.text("ui.reader.bookmarks.title"), i18n.format("ui.reader.bookmark.delete_error", rootMessage(error)));
                });
                return null;
            });
        }
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

    private record BookmarkChoice(Bookmark bookmark, String defaultTitle) {
        @Override
        public String toString() {
            String title = bookmark.getChapterTitle();
            if (title == null || title.isBlank()) title = defaultTitle;
            return String.format(Locale.ROOT, "%s — %.1f%%", title, bookmark.getPosition());
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

    private List<TocEntry> flattenToc(List<TocEntry> roots) {
        List<TocEntry> result = new ArrayList<>();
        appendToc(roots, result, 0);
        return List.copyOf(result);
    }

    private void appendToc(List<TocEntry> entries, List<TocEntry> target, int inheritedLevel) {
        if (entries == null) return;
        for (TocEntry entry : entries) {
            if (entry == null) continue;
            int level = Math.max(inheritedLevel, entry.level());
            target.add(new TocEntry(entry.title(), entry.textOffset(), level, List.of()));
            appendToc(entry.children(), target, level + 1);
        }
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
                log.warn("PDF text search failed: {}", error);
                dialogService.showError(i18n.text("ui.reader.search.title"),
                        i18n.format("ui.reader.search.error_detail", error == null ? "" : error));
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
        if (document == null || query == null || query.isBlank()) {
            if (readerSidebarSearchStatus != null) readerSidebarSearchStatus.setText(i18n.text("ui.reader.search.enter_text"));
            if (readerSidebarSearchResults != null) readerSidebarSearchResults.getItems().clear();
            return;
        }
        cancelReaderSidebarSearch();
        long generation = readerSidebarSearchGeneration.incrementAndGet();
        String bookId = currentBookId == null ? "" : currentBookId.asString();
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        if (readerSidebarSearchStatus != null) readerSidebarSearchStatus.setText(i18n.text("ui.reader.search.searching"));
        try {
            readerSidebarSearchTask = uiBackgroundExecutor.submitCancellable(() -> {
                try {
                    List<ReaderSearchService.SearchResult> results = new ReaderSearchService().search(document, query.strip());
                    Platform.runLater(() -> applySidebarSearchResults(generation, requestToken, bookId, results, null));
                } catch (Throwable error) {
                    Platform.runLater(() -> applySidebarSearchResults(generation, requestToken, bookId, List.of(), error));
                }
                return null;
            });
        } catch (RejectedExecutionException busy) {
            if (readerSidebarSearchStatus != null) readerSidebarSearchStatus.setText(i18n.text("ui.reader.search.queue_busy"));
        }
    }

    private void applySidebarSearchResults(long generation, UiAsyncRequestToken requestToken, String bookId,
                                           List<ReaderSearchService.SearchResult> results, Throwable error) {
        if (generation != readerSidebarSearchGeneration.get() || !sameOpenBook(requestToken, bookId)) return;
        if (error != null) {
            if (error instanceof CancellationException) return;
            if (readerSidebarSearchStatus != null) readerSidebarSearchStatus.setText(
                    error.getMessage() == null ? i18n.text("ui.reader.search.error") : error.getMessage());
            if (readerSidebarSearchResults != null) readerSidebarSearchResults.getItems().clear();
            return;
        }
        if (readerSidebarSearchResults != null) {
            readerSidebarSearchResults.setItems(javafx.collections.FXCollections.observableArrayList(results));
        }
        if (readerSidebarSearchStatus != null) readerSidebarSearchStatus.setText(results.isEmpty()
                ? i18n.text("ui.reader.search.nothing_found")
                : i18n.format("ui.reader.search.matches_found", results.size()));
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

    private void cancelReaderSidebarSearch() {
        readerSidebarSearchGeneration.incrementAndGet();
        Future<?> task = readerSidebarSearchTask;
        readerSidebarSearchTask = null;
        if (task != null && !task.isDone()) task.cancel(true);
    }

    private void refreshBookMapSidebar() {
        if (bookMapSidebarList == null || readerView == null || !readerView.isBookOpen()) return;
        ReaderDocument document = readerView.getEngine().getCurrentDocument();
        if (document == null || document.chapters() == null) return;
        long currentOffset = readerView.getCurrentPosition() == null ? 0L : readerView.getCurrentPosition().textOffset();
        List<BookMapRow> rows = new ArrayList<>();
        for (int i = 0; i < document.chapters().size(); i++) {
            ChapterIndex chapter = document.chapters().get(i);
            int notes = 0;
            int highlights = 0;
            for (ReaderAnnotationOverlay annotation : currentAnnotationPresentation.overlays()) {
                if (!chapter.containsOffset(annotation.startOffset())) continue;
                if (annotation.note()) notes++; else highlights++;
            }
            int bookmarks = 0;
            for (Bookmark bookmark : currentSidebarBookmarks) {
                ReaderPosition position = persistenceService.bookmarkToPosition(bookmark, document.totalTextLength());
                if (chapter.containsOffset(position.textOffset())) bookmarks++;
            }
            boolean passed = currentOffset >= chapter.endOffset();
            boolean current = chapter.containsOffset(currentOffset);
            rows.add(new BookMapRow(i, chapter.startOffset(), bookMapLabel(chapter, notes, highlights, bookmarks, passed, current)));
        }
        bookMapSidebarList.setItems(javafx.collections.FXCollections.observableArrayList(rows));
    }

    private String bookMapLabel(ChapterIndex chapter, int notes, int highlights, int bookmarks, boolean passed, boolean current) {
        String state = current ? "▶ " : passed ? "✓ " : "○ ";
        String title = chapter.title() == null || chapter.title().isBlank()
                ? i18n.text("ui.reader.bookmap.untitled_chapter") : chapter.title();
        return state + title + "   📝 " + notes + "   ▰ " + highlights + "   🔖 " + bookmarks;
    }

    @FXML
    public void goToBookMapRow() {
        BookMapRow row = bookMapSidebarList == null ? null : bookMapSidebarList.getSelectionModel().getSelectedItem();
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

    private record BookMapRow(int chapterIndex, long startOffset, String label) {
        @Override public String toString() { return label; }
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

        if (positionAutosaver != null) {
            positionAutosaver.close();
            positionAutosaver = null;
        }
        if (audioPositionAutosaver != null) {
            audioPositionAutosaver.close();
            audioPositionAutosaver = null;
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
