package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.audio.AudioDocumentSession;
import com.myhomelibcorp.reader.audio.AudioReaderView;
import com.myhomelibcorp.reader.render.comic.ComicReaderView;
import com.myhomelibcorp.reader.render.comic.ComicDocumentSession;
import com.myhomelibcorp.reader.render.pdf.PdfReaderView;
import com.myhomelibcorp.reader.render.pdf.PdfDocumentSession;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReaderProgrammaticActionContractTest {
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void closeStage() throws Exception {
        if (stage != null) fx(() -> { stage.close(); stage = null; return null; });
    }

    @Test
    void readerToolbarCallbackButtonsAndMoreProxyHaveObservableEffects() throws Exception {
        ReaderCanvas canvas = mock(ReaderCanvas.class);
        ReaderEngine engine = mock(ReaderEngine.class);
        when(canvas.getEngine()).thenReturn(engine);
        when(engine.getSettings()).thenReturn(ReaderSettings.defaultSettings());
        when(canvas.isBookOpen()).thenReturn(true);
        when(canvas.isAutoScrollAllowed()).thenReturn(true);

        ReaderToolbar toolbar = fx(() -> new ReaderToolbar(canvas, key -> key));
        AtomicInteger bookmark = new AtomicInteger();
        AtomicInteger bookmarks = new AtomicInteger();
        AtomicInteger toc = new AtomicInteger();
        AtomicInteger search = new AtomicInteger();
        AtomicInteger annotations = new AtomicInteger();
        AtomicInteger bookMap = new AtomicInteger();
        AtomicInteger left = new AtomicInteger();
        AtomicInteger right = new AtomicInteger();
        AtomicInteger ttsStart = new AtomicInteger();
        AtomicInteger ttsPause = new AtomicInteger();
        AtomicInteger ttsStop = new AtomicInteger();
        AtomicReference<ReaderSettings> settings = new AtomicReference<>();
        fx(() -> {
            toolbar.setOnBookmarkClick(bookmark::incrementAndGet);
            toolbar.setOnBookmarksClick(bookmarks::incrementAndGet);
            toolbar.setOnTocClick(toc::incrementAndGet);
            toolbar.setOnSearchClick(search::incrementAndGet);
            toolbar.setOnAnnotationsClick(annotations::incrementAndGet);
            toolbar.setOnBookMapClick(bookMap::incrementAndGet);
            toolbar.setOnToggleLeftSidebarClick(left::incrementAndGet);
            toolbar.setOnToggleRightSidebarClick(right::incrementAndGet);
            toolbar.setOnTtsStartClick(ttsStart::incrementAndGet);
            toolbar.setOnTtsPauseClick(ttsPause::incrementAndGet);
            toolbar.setOnTtsStopClick(ttsStop::incrementAndGet);
            toolbar.setOnSettingsClick(settings::set);

            toolbar.getBookmarkButton().fire();
            toolbar.getBookmarksButton().fire();
            toolbar.getTocButton().fire();
            toolbar.getSearchButton().fire();
            toolbar.getAnnotationsButton().fire();
            toolbar.getBookMapButton().fire();
            toolbar.getLeftSidebarButton().fire();
            toolbar.getRightSidebarButton().fire();
            toolbar.getSettingsButton().fire();
            toolbar.getPrevPageButton().fire();
            toolbar.getNextPageButton().fire();
            toolbar.getPrevChapterButton().fire();
            toolbar.getNextChapterButton().fire();
            toolbar.getPageModeButton().fire();
            toolbar.getAutoScrollButton().fire();
            toolbar.getZoomOutButton().fire();
            toolbar.getZoomInButton().fire();
            toolbar.getZoomResetButton().fire();
            toolbar.getThemeButton().fire();
            toolbar.getTtsStartButton().fire();
            toolbar.updateTtsState(true, false);
            toolbar.getTtsPauseButton().fire();
            toolbar.getTtsStopButton().fire();
            return null;
        });

        assertThat(bookmark).hasValue(1);
        assertThat(bookmarks).hasValue(1);
        assertThat(toc).hasValue(1);
        assertThat(search).hasValue(1);
        assertThat(annotations).hasValue(1);
        assertThat(bookMap).hasValue(1);
        assertThat(left).hasValue(1);
        assertThat(right).hasValue(1);
        assertThat(settings.get()).isEqualTo(ReaderSettings.defaultSettings());
        verify(canvas).previousPage();
        verify(canvas).nextPage();
        verify(canvas).previousChapter();
        verify(canvas).nextChapter();
        verify(canvas).toggleTwoPageMode();
        verify(canvas).toggleAutoScroll();
        verify(canvas).zoomOut();
        verify(canvas).zoomIn();
        verify(canvas).resetZoom();
        verify(canvas).cycleTheme();
        assertThat(ttsStart).hasValue(1);
        assertThat(ttsPause).hasValue(1);
        assertThat(ttsStop).hasValue(1);

        MenuItem item = toolbar.getMoreButton().getItems().stream()
                .filter(i -> "ui.reader.toolbar.bookmarks".equals(i.getText())).findFirst().orElseThrow();
        fx(() -> { item.fire(); return null; });
        assertThat(bookmarks).hasValue(2);
    }

    @Test
    void readerToolbarFullscreenButtonRequestsFullscreenForOwningStage() throws Exception {
        ReaderCanvas canvas = mock(ReaderCanvas.class);
        ReaderEngine engine = mock(ReaderEngine.class);
        when(canvas.getEngine()).thenReturn(engine);
        when(engine.getSettings()).thenReturn(ReaderSettings.defaultSettings());
        AtomicReference<Stage> requestedStage = new AtomicReference<>();
        AtomicReference<Boolean> requestedFullScreen = new AtomicReference<>();
        ReaderToolbar toolbar = fx(() -> new ReaderToolbar(canvas, key -> key, (owner, enabled) -> {
            requestedStage.set(owner);
            requestedFullScreen.set(enabled);
        }));

        fx(() -> {
            // Detached toolbar must not send a fullscreen request.
            toolbar.getFullscreenButton().fire();
            assertThat(requestedStage.get()).isNull();

            stage = new Stage();
            stage.setScene(new Scene(new StackPane(toolbar), 640, 480));
            assertThat(stage.isFullScreen()).isFalse();
            toolbar.getFullscreenButton().fire();
            return null;
        });
        assertThat(requestedStage).hasValue(stage);
        assertThat(requestedFullScreen).hasValue(true);
        // The host OS may reject fullscreen in a non-interactive CI session.
        // The real Stage transition remains part of physical Windows acceptance.
    }

    @Test
    void pdfToolbarCallbacksFireExactWorkspaceHooks() throws Exception {
        PdfReaderView view = fx(() -> new PdfReaderView(key -> key));
        AtomicInteger back = new AtomicInteger(), bookmark = new AtomicInteger(), bookmarks = new AtomicInteger(), toc = new AtomicInteger(), search = new AtomicInteger();
        fx(() -> {
            view.setOnBack(back::incrementAndGet);
            view.setOnAddBookmark(bookmark::incrementAndGet);
            view.setOnBookmarks(bookmarks::incrementAndGet);
            view.setOnToc(toc::incrementAndGet);
            view.setOnSearch(search::incrementAndGet);
            fireTopButton(view, "ui.reader.back");
            fireTopButton(view, "ui.reader.toolbar.add_bookmark");
            fireTopButton(view, "ui.reader.toolbar.bookmarks");
            fireTopButton(view, "ui.reader.toolbar.toc");
            fireTopButton(view, "ui.reader.toolbar.search");
            return null;
        });
        assertThat(back).hasValue(1);
        assertThat(bookmark).hasValue(1);
        assertThat(bookmarks).hasValue(1);
        assertThat(toc).hasValue(1);
        assertThat(search).hasValue(1);
        fx(() -> { view.close(); return null; });
    }

    @Test
    void comicToolbarCallbacksFireExactWorkspaceHooks() throws Exception {
        ComicReaderView view = fx(() -> new ComicReaderView(key -> key));
        AtomicInteger back = new AtomicInteger(), bookmark = new AtomicInteger(), bookmarks = new AtomicInteger();
        fx(() -> {
            view.setOnBack(back::incrementAndGet);
            view.setOnAddBookmark(bookmark::incrementAndGet);
            view.setOnBookmarks(bookmarks::incrementAndGet);
            fireTopButton(view, "ui.reader.back");
            fireTopButton(view, "ui.reader.toolbar.add_bookmark");
            fireTopButton(view, "ui.reader.toolbar.bookmarks");
            return null;
        });
        assertThat(back).hasValue(1);
        assertThat(bookmark).hasValue(1);
        assertThat(bookmarks).hasValue(1);
        fx(() -> { view.close(); return null; });
    }

    @Test
    void audioToolbarCallbacksAndSleepTimerHaveObservableEffects() throws Exception {
        AudioReaderView view = fx(() -> new AudioReaderView(key -> key));
        AtomicInteger back = new AtomicInteger(), bookmark = new AtomicInteger(), bookmarks = new AtomicInteger();
        AudioDocumentSession session = mock(AudioDocumentSession.class);
        setField(view, "session", session);
        @SuppressWarnings("unchecked") ComboBox<Integer> sleep = (ComboBox<Integer>) getField(view, "sleep");
        fx(() -> {
            view.setOnBack(back::incrementAndGet);
            view.setOnAddBookmark(bookmark::incrementAndGet);
            view.setOnBookmarks(bookmarks::incrementAndGet);
            fireTopButton(view, "ui.reader.audio.back");
            fireTopButton(view, "ui.reader.audio.bookmark");
            fireTopButton(view, "ui.reader.audio.bookmarks");
            sleep.setValue(20);
            sleep.getOnAction().handle(new ActionEvent());
            return null;
        });
        assertThat(back).hasValue(1);
        assertThat(bookmark).hasValue(1);
        assertThat(bookmarks).hasValue(1);
        verify(session, atLeastOnce()).setSleepTimer(Duration.ofMinutes(20));
        setField(view, "session", null);
        fx(() -> { view.close(); return null; });
    }

    @Test
    void pdfAndComicZoomAndThumbnailActionsMutateViewState() throws Exception {
        PdfReaderView pdf = fx(() -> new PdfReaderView(key -> key));
        ComicReaderView comic = fx(() -> new ComicReaderView(key -> key));
        fx(() -> {
            fireTopButton(pdf, "+");
            fireTopButton(comic, "+");
            return null;
        });
        assertThat((double) getField(pdf, "zoom")).isGreaterThan(1.0);
        assertThat((double) getField(comic, "zoom")).isGreaterThan(1.0);

        Button pdfZoomReset = (Button) getField(pdf, "zoomReset");
        Button comicZoomReset = (Button) getField(comic, "zoomReset");
        javafx.scene.control.ToggleButton pdfThumbnails =
                (javafx.scene.control.ToggleButton) getField(pdf, "thumbnailsToggle");
        javafx.scene.control.ToggleButton comicThumbnails =
                (javafx.scene.control.ToggleButton) getField(comic, "thumbnailsToggle");
        javafx.scene.control.ToggleButton comicDualPage =
                (javafx.scene.control.ToggleButton) getField(comic, "dualPage");
        fx(() -> {
            fireTopButton(pdf, "−");
            fireTopButton(comic, "−");
            pdfZoomReset.fire();
            comicZoomReset.fire();
            pdfThumbnails.fire();
            comicThumbnails.fire();
            comicDualPage.fire();
            return null;
        });
        assertThat((double) getField(pdf, "zoom")).isEqualTo(1.0);
        assertThat((double) getField(comic, "zoom")).isEqualTo(1.0);
        assertThat(((javafx.scene.Node) getField(pdf, "thumbnails")).isVisible()).isTrue();
        assertThat(((javafx.scene.Node) getField(comic, "thumbnails")).isVisible()).isTrue();
        assertThat((boolean) getField(comic, "dualPageMode")).isTrue();
        fx(() -> { pdf.close(); comic.close(); return null; });
    }


    @Test
    void comicPreviousNext_goRelativeChangesPageWithoutRenderingExternalAssets() throws Exception {
        ComicReaderView view = fx(() -> new ComicReaderView(key -> key));
        ComicDocumentSession session = mock(ComicDocumentSession.class);
        when(session.pageCount()).thenReturn(5);
        setField(view, "session", session);
        setField(view, "pageIndex", 2);
        ToggleButton continuous = (ToggleButton) getField(view, "continuous");
        Button previous = (Button) getField(view, "previous");
        Button next = (Button) getField(view, "next");
        fx(() -> { continuous.setSelected(true); previous.fire(); return null; });
        assertThat(view.currentPageIndex()).isEqualTo(1);
        fx(() -> { next.fire(); return null; });
        assertThat(view.currentPageIndex()).isEqualTo(2);
        setField(view, "session", null);
        fx(() -> { view.close(); return null; });
    }

    @Test
    void pdfPreviousNext_goToPageAnd_switchContinuousMutateReaderState() throws Exception {
        PdfReaderView view = fx(() -> new PdfReaderView(key -> key));
        PdfDocumentSession session = mock(PdfDocumentSession.class);
        when(session.pageCount()).thenReturn(5);
        setField(view, "session", session);
        setField(view, "pageIndex", 2);
        ToggleButton continuous = (ToggleButton) getField(view, "continuous");
        Button previous = (Button) getField(view, "previous");
        Button next = (Button) getField(view, "next");
        fx(() -> { continuous.setSelected(true); previous.fire(); return null; });
        assertThat(view.currentPageIndex()).isEqualTo(1);
        fx(() -> { next.fire(); return null; });
        assertThat(view.currentPageIndex()).isEqualTo(2);
        fx(() -> { continuous.setSelected(false); continuous.fire(); return null; });
        assertThat(view.getCenter()).isSameAs(getField(view, "continuousList"));
        setField(view, "session", null);
        fx(() -> { view.close(); return null; });
    }


    @Test
    void readerCanvasClearMenuActionClearsSelectionAndNotifiesWhenSelectionExisted() throws Exception {
        ReaderEngine engine = mock(ReaderEngine.class);
        JavaFxReaderRenderer renderer = mock(JavaFxReaderRenderer.class);
        when(engine.getSettings()).thenReturn(ReaderSettings.defaultSettings());
        when(engine.isOpen()).thenReturn(false);
        when(renderer.getCanvas()).thenReturn(new Canvas());
        ReaderCanvas canvas = fx(() -> new ReaderCanvas(engine, renderer, key -> key));
        ReaderSelectionController selection = mock(ReaderSelectionController.class);
        when(selection.hasSelection()).thenReturn(true);
        setField(canvas, "selectionController", selection);
        @SuppressWarnings("unchecked") javafx.scene.control.ContextMenu menu =
                (javafx.scene.control.ContextMenu) getField(canvas, "selectionContextMenu");
        MenuItem clear = menu.getItems().stream()
                .filter(item -> "ui.reader.selection.clear".equals(item.getText()))
                .findFirst().orElseThrow();
        fx(() -> { clear.fire(); return null; });
        verify(selection).clear();
        verify(selection, atLeastOnce()).hasSelection();
    }

    @Test
    void readerCanvasCopy_copyToClipboardDelegatesToSelectionController() throws Exception {
        ReaderEngine engine = mock(ReaderEngine.class);
        JavaFxReaderRenderer renderer = mock(JavaFxReaderRenderer.class);
        when(engine.getSettings()).thenReturn(ReaderSettings.defaultSettings());
        when(renderer.getCanvas()).thenReturn(new Canvas());
        ReaderCanvas canvas = fx(() -> new ReaderCanvas(engine, renderer, key -> key));
        ReaderSelectionController selection = mock(ReaderSelectionController.class);
        setField(canvas, "selectionController", selection);

        fx(() -> { canvas.copySelectionFromInput(); return null; });

        verify(selection).copyToClipboard();
    }

    @Test
    void audioPlayPauseAndSpeedActionsReachSession() throws Exception {
        AudioReaderView view = fx(() -> new AudioReaderView(key -> key));
        AudioDocumentSession session = mock(AudioDocumentSession.class);
        when(session.isPlaying()).thenReturn(false, true);
        when(session.currentPosition()).thenReturn(new com.myhomelibcorp.reader.audio.AudioPosition(0L, 0, 0));
        setField(view, "session", session);
        Button playPause = (Button) getField(view, "playPause");
        @SuppressWarnings("unchecked") ComboBox<Double> speed = (ComboBox<Double>) getField(view, "speed");
        fx(() -> {
            playPause.fire();
            speed.setValue(1.5);
            speed.fireEvent(new ActionEvent());
            return null;
        });
        verify(session).play();
        verify(session).setRate(1.5);
        setField(view, "session", null);
        fx(() -> { view.close(); return null; });
    }

    @Test
    void audioChaptersActionSeeksToSelectedChapter() throws Exception {
        AudioReaderView view = fx(() -> new AudioReaderView(key -> key));
        AudioDocumentSession session = mock(AudioDocumentSession.class);
        var track = new com.myhomelibcorp.reader.audio.AudioTrack(
                java.nio.file.Path.of("chapter-test.m4b"), "Track", 10_000L,
                java.util.List.of(
                        new com.myhomelibcorp.reader.audio.AudioChapter("One", 0L, 5_000L),
                        new com.myhomelibcorp.reader.audio.AudioChapter("Two", 5_000L, 10_000L)));
        when(session.tracks()).thenReturn(java.util.List.of(track));
        when(session.currentPosition()).thenReturn(new com.myhomelibcorp.reader.audio.AudioPosition(0L, 0, 0));
        when(session.totalDurationMillis()).thenReturn(10_000L);
        when(session.progressPercent()).thenReturn(0.0);
        when(session.absoluteMillis(any())).thenReturn(0L);

        fx(() -> { view.openPrepared(session, null); return null; });
        @SuppressWarnings("unchecked") ComboBox<Object> chapters = (ComboBox<Object>) getField(view, "chapters");
        Object second = fx(() -> chapters.getItems().get(1));
        fx(() -> { chapters.setValue(second); chapters.fireEvent(new ActionEvent()); return null; });

        verify(session).seek(new com.myhomelibcorp.reader.audio.AudioPosition(5_000L, 0, 1));
        setField(view, "session", null);
        fx(() -> { view.close(); return null; });
    }

    private static void fireTopButton(javafx.scene.layout.BorderPane view, String text) {
        HBox top = (HBox) view.getTop();
        Button button = top.getChildren().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> text.equals(b.getText())).findFirst().orElseThrow(() -> new AssertionError("missing button " + text));
        button.fire();
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static <T> T fx(Supplier<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.get();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(action.get()); } catch (Throwable t) { error.set(t); } finally { done.countDown(); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException r) throw r;
            if (error.get() instanceof Error e) throw e;
            throw new RuntimeException(error.get());
        }
        return result.get();
    }
}
