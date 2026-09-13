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
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderSelection;
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
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.ui.navigation.WorkspaceLifecycle;
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
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
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
    private final DictionaryLookupService dictionaryLookupService;
    private final TranslationService translationService;
    private final TtsPlaybackService ttsPlaybackService;
    private final ApplicationContext springContext;
    private final UiBackgroundExecutor uiBackgroundExecutor;
    private final ApplicationState appState;
    private final MainLayoutService mainLayoutService;
    private final LocalizationService i18n;

    @FXML
    private StackPane readerContainer;

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
    private String contentTargetArtifactId;
    private Long contentTargetOffset;

    @FXML
    public void initialize() {
        log.info("📖 NewReaderWorkspaceController ініціалізовано");
        positionAutosaver = new ReaderPositionAutosaver(persistenceService);
        audioPositionAutosaver = new AudioPositionAutosaver(persistenceService);
        initializeReaderView();
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
        readerView.setOnHighlightRequested(this::createHighlightFromSelection);
        readerView.setOnNoteRequested(this::createNoteFromSelection);
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

    private void createHighlightFromSelection(ReaderSelection selection) {
        if (!annotationActionAvailable(selection)) return;
        String bookId = currentBookId.asString();
        var anchor = ReaderAnnotationPresenter.anchor(bookId, currentReaderArtifactId, selection);
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        uiBackgroundExecutor.submit(() -> annotationService.createHighlight(anchor, AnnotationService.DEFAULT_COLOR, Set.of()))
                .thenAccept(saved -> Platform.runLater(() -> {
                    if (!sameOpenBook(requestToken, bookId)) return;
                    readerView.clearTextSelection();
                    appState.getStatusBar().setStatusText(i18n.text("ui.reader.annotation.highlight_saved"));
                    refreshAnnotationsAsync();
                }))
                .exceptionally(error -> {
                    handleAnnotationSaveFailure(requestToken, bookId, error);
                    return null;
                });
    }

    private void createNoteFromSelection(ReaderSelection selection) {
        if (!annotationActionAvailable(selection)) return;
        TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle(i18n.text("ui.reader.annotation.note.title"));
        dialog.setHeaderText(i18n.text("ui.reader.annotation.note.header"));
        dialog.setContentText(i18n.text("ui.reader.annotation.note.label"));
        Optional<String> response = dialog.showAndWait();
        if (response.isEmpty() || response.get() == null || response.get().isBlank()) return;

        String note = response.get().trim();
        String bookId = currentBookId.asString();
        var anchor = ReaderAnnotationPresenter.anchor(bookId, currentReaderArtifactId, selection);
        UiAsyncRequestToken requestToken = UiAsyncRequestGuard.snapshot(openGeneration, appState);
        uiBackgroundExecutor.submit(() -> annotationService.createNote(anchor, AnnotationService.DEFAULT_COLOR, note, Set.of()))
                .thenAccept(saved -> Platform.runLater(() -> {
                    if (!sameOpenBook(requestToken, bookId)) return;
                    readerView.clearTextSelection();
                    appState.getStatusBar().setStatusText(i18n.text("ui.reader.annotation.note_saved"));
                    refreshAnnotationsAsync();
                }))
                .exceptionally(error -> {
                    handleAnnotationSaveFailure(requestToken, bookId, error);
                    return null;
                });
    }

    private boolean annotationActionAvailable(ReaderSelection selection) {
        return selectionActionAvailable(selection);
    }

    private void handleAnnotationSaveFailure(UiAsyncRequestToken requestToken, String bookId, Throwable error) {
        log.error("Не вдалося зберегти annotation для книги {}", bookId, error);
        Platform.runLater(() -> {
            if (sameOpenBook(requestToken, bookId)) {
                dialogService.showError(i18n.text("common.error"),
                        i18n.format("ui.reader.annotation.save_failed", rootMessage(error)));
            }
        });
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
        uiBackgroundExecutor.submit(() -> ReaderAnnotationPresenter.overlays(
                        annotationService.listBookAnnotationViews(bookId), artifactId, document))
                .thenAccept(overlays -> Platform.runLater(() -> {
                    if (!sameOpenBook(requestToken, bookId)) return;
                    readerView.setAnnotationOverlays(overlays);
                    jumpToAnnotationTarget(document, overlays);
                }))
                .exceptionally(error -> {
                    log.warn("Не вдалося завантажити annotations для книги {}: {}", bookId, rootMessage(error));
                    return null;
                });
    }

    private void jumpToAnnotationTarget(ReaderDocument document, List<com.myhomelibcorp.reader.api.ReaderAnnotationOverlay> overlays) {
        String target = annotationTargetId;
        if (target == null || document == null || overlays == null || readerView == null) return;
        var match = overlays.stream().filter(item -> target.equals(item.id())).findFirst().orElse(null);
        annotationTargetId = null;
        if (match == null) {
            appState.getStatusBar().setStatusText(i18n.text("ui.annotations.jump_unavailable"));
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
        if (currentPdf) {
            showPdfToc();
            return;
        }
        if (isDisposed || readerView == null || !readerView.isBookOpen()) {
            return;
        }

        try {
            var document = readerView.getEngine().getCurrentDocument();
            if (document == null || document.toc() == null || document.toc().isEmpty()) {
                dialogService.showInfo(i18n.text("ui.reader.toc.title"), i18n.text("ui.reader.toc.empty"));
                return;
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/toc-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            TOCDialogController controller = loader.getController();
            controller.setEntries(document.toc().entries(), entry -> {
                ReaderPosition pos = new ReaderPosition(
                        Math.max(0, document.chapterIndexAt(entry.textOffset())),
                        entry.textOffset(),
                        0,
                        0
                );
                readerView.goToPosition(pos);
                positionChanged = true;
            });

            Stage stage = new Stage();
            stage.setTitle(i18n.text("ui.reader.toc.title"));
            stage.setScene(new Scene(root, 400, 500));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(readerContainer.getScene().getWindow());
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття змісту", e);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.reader.toc.open_error", e.getMessage()));
        }
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
        if (currentPdf) {
            showPdfSearch();
            return;
        }
        if (isDisposed || readerView == null || !readerView.isBookOpen()) {
            return;
        }

        try {
            var document = readerView.getEngine().getCurrentDocument();
            if (document == null) {
                return;
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/search-dialog.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            SearchDialogController controller = loader.getController();
            controller.setDocument(document, pos -> {
                readerView.goToPosition(pos);
                positionChanged = true;
            });

            Stage stage = new Stage();
            stage.setTitle(i18n.text("ui.reader.search.title"));
            stage.setScene(new Scene(root, 500, 450));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(readerContainer.getScene().getWindow());
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття пошуку", e);
            dialogService.showError(i18n.text("common.error"), i18n.format("ui.reader.search.open_error", e.getMessage()));
        }
    }

    @Override
    public void dispose() {
        if (isDisposed) {
            return;
        }
        isDisposed = true;
        openGeneration.incrementAndGet();
        cancelPendingOpen();
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
