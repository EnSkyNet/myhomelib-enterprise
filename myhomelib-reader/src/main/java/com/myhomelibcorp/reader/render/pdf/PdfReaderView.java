package com.myhomelibcorp.reader.render.pdf;

import com.myhomelibcorp.reader.api.ReaderPosition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.util.Callback;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** JavaFX PDF renderer adapter with lazy background rasterization and bounded session cache. */
public final class PdfReaderView extends BorderPane implements AutoCloseable {
    private final Function<String, String> text;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-reader-render");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong singleRenderGeneration = new AtomicLong();
    private final java.util.Set<Future<?>> renderTasks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Future<?>> documentTasks = ConcurrentHashMap.newKeySet();

    private final Button back = new Button();
    private final Button previous = new Button("‹");
    private final Button next = new Button("›");
    private final Label pageLabel = new Label();
    private final Button zoomOut = new Button("−");
    private final Button zoomIn = new Button("+");
    private final Button zoomReset = new Button("100%");
    private final ToggleButton fitWidth = new ToggleButton();
    private final ToggleButton fitPage = new ToggleButton();
    private final ToggleButton continuous = new ToggleButton();
    private final ToggleButton thumbnailsToggle = new ToggleButton();
    private final Button addBookmark = new Button();
    private final Button bookmarks = new Button();
    private final Button toc = new Button();
    private final Button search = new Button();
    private final ProgressIndicator progress = new ProgressIndicator();

    private final ImageView singleImage = new ImageView();
    private final StackPane singlePane = new StackPane(singleImage);
    private final ScrollPane singleScroll = new ScrollPane(singlePane);
    private final ListView<Integer> continuousList = new ListView<>();
    private final ListView<Integer> thumbnails = new ListView<>();

    private PdfDocumentSession session;
    private int pageIndex;
    private double zoom = 1.0;
    private FitMode fitMode = FitMode.PAGE;
    private Future<?> singleRenderTask;
    private Consumer<ReaderPosition> onPositionChanged = ignored -> { };
    private Runnable onBack = () -> { };
    private Runnable onAddBookmark = () -> { };
    private Runnable onBookmarks = () -> { };
    private Runnable onToc = () -> { };
    private Runnable onSearch = () -> { };
    private Consumer<String> onError = ignored -> { };
    private volatile boolean closed;

    public PdfReaderView(Function<String, String> text) {
        this.text = text == null ? Function.identity() : text;
        buildUi();
    }

    private void buildUi() {
        back.setText(t("ui.reader.back"));
        fitWidth.setText(t("ui.reader.pdf.fit_width"));
        fitPage.setText(t("ui.reader.pdf.fit_page"));
        continuous.setText(t("ui.reader.pdf.continuous"));
        thumbnailsToggle.setText(t("ui.reader.pdf.thumbnails"));
        addBookmark.setText(t("ui.reader.toolbar.add_bookmark"));
        bookmarks.setText(t("ui.reader.toolbar.bookmarks"));
        toc.setText(t("ui.reader.toolbar.toc"));
        search.setText(t("ui.reader.toolbar.search"));
        progress.setPrefSize(22, 22);
        progress.setVisible(false);
        progress.setManaged(false);

        ToggleGroup fitGroup = new ToggleGroup();
        fitWidth.setToggleGroup(fitGroup);
        fitPage.setToggleGroup(fitGroup);
        fitPage.setSelected(true);
        fitGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                fitMode = FitMode.NONE;
            } else if (now == fitWidth) fitMode = FitMode.WIDTH;
            else fitMode = FitMode.PAGE;
            rerenderVisible();
        });

        HBox bar = new HBox(6, back, previous, next, pageLabel, zoomOut, zoomIn, zoomReset,
                fitWidth, fitPage, continuous, thumbnailsToggle, addBookmark, bookmarks, toc, search, progress);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));
        setTop(bar);

        singleImage.setPreserveRatio(true);
        singlePane.setAlignment(Pos.TOP_CENTER);
        singlePane.setPadding(new Insets(12));
        singleScroll.setFitToWidth(true);
        singleScroll.setFitToHeight(true);
        setCenter(singleScroll);

        continuousList.setCellFactory(pdfPageCellFactory(false));
        thumbnails.setCellFactory(pdfPageCellFactory(true));
        thumbnails.setPrefWidth(160);
        thumbnails.setVisible(false);
        thumbnails.setManaged(false);
        setLeft(thumbnails);

        back.setOnAction(e -> onBack.run());
        previous.setOnAction(e -> goToPage(pageIndex - 1));
        next.setOnAction(e -> goToPage(pageIndex + 1));
        zoomOut.setOnAction(e -> setZoom(zoom / 1.2));
        zoomIn.setOnAction(e -> setZoom(zoom * 1.2));
        zoomReset.setOnAction(e -> { fitGroup.selectToggle(null); setZoom(1.0); });
        continuous.setOnAction(e -> switchContinuous(continuous.isSelected()));
        thumbnailsToggle.setOnAction(e -> {
            boolean show = thumbnailsToggle.isSelected();
            thumbnails.setVisible(show); thumbnails.setManaged(show);
            if (fitMode != FitMode.NONE) rerenderVisible();
        });
        addBookmark.setOnAction(e -> onAddBookmark.run());
        bookmarks.setOnAction(e -> onBookmarks.run());
        toc.setOnAction(e -> onToc.run());
        search.setOnAction(e -> onSearch.run());
        thumbnails.getSelectionModel().selectedItemProperty().addListener((obs, old, page) -> {
            if (page != null) goToPage(page);
        });
        continuousList.getSelectionModel().selectedItemProperty().addListener((obs, old, page) -> {
            if (page != null && continuous.isSelected()) setCurrentPage(page, true);
        });
        continuousList.setOnScroll(event -> Platform.runLater(this::syncContinuousPageFromViewport));
        continuousList.setOnMouseReleased(event -> {
            if (continuous.isSelected()) Platform.runLater(this::syncContinuousPageFromViewport);
        });

        ChangeListener<Number> viewportListener = (obs, old, value) -> {
            if (fitMode != FitMode.NONE) rerenderVisible();
        };
        singleScroll.viewportBoundsProperty().addListener((obs, old, value) -> {
            if (fitMode != FitMode.NONE) rerenderVisible();
        });
        widthProperty().addListener(viewportListener);
        heightProperty().addListener(viewportListener);
    }

    public void openPrepared(PdfDocumentSession prepared, ReaderPosition savedPosition) {
        Objects.requireNonNull(prepared, "prepared");
        if (closed) {
            try { prepared.close(); } catch (IOException ignored) { }
            throw new IllegalStateException("PDF Reader view is closed");
        }
        closeDocument();
        session = prepared;
        List<Integer> pages = new ArrayList<>(prepared.pageCount());
        for (int i = 0; i < prepared.pageCount(); i++) pages.add(i);
        continuousList.setItems(FXCollections.observableArrayList(pages));
        thumbnails.setItems(FXCollections.observableArrayList(pages));
        int restored = PdfPagePosition.restorePageIndex(savedPosition, prepared.pageCount());
        pageIndex = restored;
        updateState();
        renderSinglePage();
        Platform.runLater(() -> thumbnails.scrollTo(pageIndex));
    }

    public boolean isOpen() { return session != null; }
    public int pageCount() { return session == null ? 0 : session.pageCount(); }
    public int currentPageIndex() { return pageIndex; }
    public ReaderPosition currentPosition() { return PdfPagePosition.toReaderPosition(pageIndex); }
    public double progressPercent() { return PdfPagePosition.progressPercent(pageIndex, pageCount()); }
    public List<PdfOutlineEntry> outlineEntries() { return session == null ? List.of() : session.outlineEntries(); }

    public void goToPosition(ReaderPosition position) {
        if (session == null) return;
        goToPage(PdfPagePosition.restorePageIndex(position, session.pageCount()));
    }

    public Future<?> searchTextAsync(String query, int maxResults, Consumer<PdfSearchOutcome> onSuccess,
                                     Consumer<String> onFailure) {
        PdfDocumentSession current = session;
        if (current == null) return null;
        Consumer<PdfSearchOutcome> success = onSuccess == null ? ignored -> { } : onSuccess;
        Consumer<String> failure = onFailure == null ? ignored -> { } : onFailure;
        long token = generation.get();
        return submitTrackedDocumentTask(() -> {
            try {
                PdfSearchOutcome outcome = current.searchText(query, maxResults);
                Platform.runLater(() -> {
                    if (session == current && token == generation.get()) success.accept(outcome);
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (session == current && token == generation.get()) {
                        failure.accept(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    }
                });
            }
        });
    }

    public void setOnPositionChanged(Consumer<ReaderPosition> listener) {
        onPositionChanged = listener == null ? ignored -> { } : listener;
    }
    public void setOnBack(Runnable listener) { onBack = listener == null ? () -> { } : listener; }
    public void setOnAddBookmark(Runnable listener) { onAddBookmark = listener == null ? () -> { } : listener; }
    public void setOnBookmarks(Runnable listener) { onBookmarks = listener == null ? () -> { } : listener; }
    public void setOnToc(Runnable listener) { onToc = listener == null ? () -> { } : listener; }
    public void setOnSearch(Runnable listener) { onSearch = listener == null ? () -> { } : listener; }
    public void setOnError(Consumer<String> listener) { onError = listener == null ? ignored -> { } : listener; }

    public void goToPage(int target) {
        if (session == null) return;
        int safe = Math.max(0, Math.min(session.pageCount() - 1, target));
        setCurrentPage(safe, true);
        if (continuous.isSelected()) {
            continuousList.getSelectionModel().select(safe);
            continuousList.scrollTo(safe);
        } else renderSinglePage();
        thumbnails.getSelectionModel().select(safe);
        thumbnails.scrollTo(safe);
    }

    private void setCurrentPage(int page, boolean notify) {
        if (session == null || page == pageIndex) { updateState(); return; }
        pageIndex = page;
        updateState();
        if (notify) onPositionChanged.accept(currentPosition());
    }

    private void setZoom(double value) {
        fitMode = FitMode.NONE;
        fitWidth.setSelected(false); fitPage.setSelected(false);
        zoom = Math.max(0.5, Math.min(4.0, value));
        zoomReset.setText(Math.round(zoom * 100) + "%");
        if (session != null) session.clearCache();
        rerenderVisible();
    }

    private void switchContinuous(boolean enabled) {
        generation.incrementAndGet();
        singleRenderGeneration.incrementAndGet();
        cancelOutstandingRenders();
        cancelSingleRender();
        if (enabled) {
            progress.setVisible(false); progress.setManaged(false);
            setCenter(continuousList);
            continuousList.getSelectionModel().select(pageIndex);
            Platform.runLater(() -> continuousList.scrollTo(pageIndex));
        } else {
            setCenter(singleScroll);
            renderSinglePage();
        }
        thumbnails.refresh();
        updateState();
    }


    private void syncContinuousPageFromViewport() {
        if (session == null || !continuous.isSelected() || continuousList.getScene() == null) return;
        Bounds viewport = continuousList.localToScene(continuousList.getBoundsInLocal());
        Integer visiblePage = null;
        double nearestTop = Double.POSITIVE_INFINITY;
        for (Node node : continuousList.lookupAll(".list-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.isEmpty() || !(cell.getItem() instanceof Integer page)) continue;
            Bounds cellBounds = cell.localToScene(cell.getBoundsInLocal());
            if (cellBounds.getMaxY() <= viewport.getMinY() || cellBounds.getMinY() >= viewport.getMaxY()) continue;
            double top = Math.max(cellBounds.getMinY(), viewport.getMinY());
            if (top < nearestTop) {
                nearestTop = top;
                visiblePage = page;
            }
        }
        if (visiblePage != null) setCurrentPage(visiblePage, true);
    }

    private void rerenderVisible() {
        if (session == null) return;
        generation.incrementAndGet();
        singleRenderGeneration.incrementAndGet();
        cancelOutstandingRenders();
        cancelSingleRender();
        if (continuous.isSelected()) {
            continuousList.refresh();
            thumbnails.refresh();
        } else {
            renderSinglePage();
            thumbnails.refresh();
        }
    }

    private void renderSinglePage() {
        PdfDocumentSession current = session;
        if (current == null || continuous.isSelected()) return;
        cancelSingleRender();
        long configToken = generation.get();
        long token = singleRenderGeneration.incrementAndGet();
        int requestedPage = pageIndex;
        double scale = scaleFor(requestedPage, false);
        progress.setVisible(true); progress.setManaged(true);
        singleRenderTask = submitTrackedRender(() -> {
            try {
                PdfRenderedPage rendered = current.render(requestedPage, scale);
                Platform.runLater(() -> {
                    if (session != current || configToken != generation.get()
                            || token != singleRenderGeneration.get() || requestedPage != pageIndex) return;
                    singleImage.setImage(toFxImage(rendered));
                    progress.setVisible(false); progress.setManaged(false);
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (session == current && configToken == generation.get()
                            && token == singleRenderGeneration.get()) {
                        progress.setVisible(false); progress.setManaged(false);
                        onError.accept(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    }
                });
            }
        });
    }

    private Callback<ListView<Integer>, ListCell<Integer>> pdfPageCellFactory(boolean thumbnail) {
        return list -> new ListCell<>() {
            private final ImageView image = new ImageView();
            private Future<?> task;
            private int requested = -1;
            {
                image.setPreserveRatio(true);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setGraphic(image);
            }
            @Override protected void updateItem(Integer page, boolean empty) {
                super.updateItem(page, empty);
                if (task != null) task.cancel(true);
                requested = page == null ? -1 : page;
                image.setImage(null);
                setText(null);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                if (empty || page == null || session == null) { setGraphic(null); return; }
                setGraphic(image);
                PdfDocumentSession current = session;
                long token = generation.get();
                double scale = scaleFor(page, thumbnail);
                task = submitTrackedRender(() -> {
                    try {
                        PdfRenderedPage rendered = current.render(page, scale);
                        Platform.runLater(() -> {
                            if (session != current || token != generation.get() || requested != page || !Objects.equals(getItem(), page)) return;
                            image.setImage(toFxImage(rendered));
                            if (thumbnail) image.setFitWidth(128);
                            else image.setFitWidth(0);
                        });
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } catch (Exception error) {
                        Platform.runLater(() -> {
                            if (session == current && token == generation.get() && requested == page) {
                                setGraphic(null);
                                setContentDisplay(ContentDisplay.TEXT_ONLY);
                                setText(t("ui.reader.pdf.render_error"));
                            }
                        });
                    }
                });
            }
        };
    }

    private double scaleFor(int page, boolean thumbnail) {
        PdfDocumentSession current = session;
        if (current == null) return 1.0;
        if (thumbnail) {
            PdfPageSize size = current.pageSize(page);
            return Math.max(0.5, Math.min(1.0, 128.0 / size.widthPoints()));
        }
        if (fitMode == FitMode.NONE) return zoom;
        PdfPageSize size = current.pageSize(page);
        double availableWidth = Math.max(120.0, getWidth() - (thumbnails.isManaged() ? thumbnails.getWidth() : 0.0) - 40.0);
        double availableHeight = Math.max(120.0, getHeight() - 90.0);
        double widthScale = availableWidth / size.widthPoints();
        if (fitMode == FitMode.WIDTH) return clampScale(widthScale);
        return clampScale(Math.min(widthScale, availableHeight / size.heightPoints()));
    }

    private static double clampScale(double scale) { return Math.max(0.5, Math.min(4.0, scale)); }

    private static WritableImage toFxImage(PdfRenderedPage rendered) {
        WritableImage image = new WritableImage(rendered.width(), rendered.height());
        image.getPixelWriter().setPixels(0, 0, rendered.width(), rendered.height(),
                PixelFormat.getIntArgbInstance(), rendered.argb(), 0, rendered.width());
        return image;
    }

    private void updateState() {
        int count = pageCount();
        pageLabel.setText(count == 0 ? "0 / 0" : (pageIndex + 1) + " / " + count);
        previous.setDisable(count == 0 || pageIndex <= 0);
        next.setDisable(count == 0 || pageIndex >= count - 1);
        zoomReset.setText(Math.round(zoom * 100) + "%");
    }

    private Future<?> submitTrackedRender(Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null) {
            @Override protected void done() { renderTasks.remove(this); }
        };
        renderTasks.add(task);
        try {
            renderExecutor.execute(task);
            return task;
        } catch (RejectedExecutionException error) {
            renderTasks.remove(task);
            throw error;
        }
    }

    private Future<?> submitTrackedDocumentTask(Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null) {
            @Override protected void done() { documentTasks.remove(this); }
        };
        documentTasks.add(task);
        try {
            renderExecutor.execute(task);
            return task;
        } catch (RejectedExecutionException error) {
            documentTasks.remove(task);
            throw error;
        }
    }

    private void cancelOutstandingDocumentTasks() {
        for (Future<?> task : List.copyOf(documentTasks)) {
            if (!task.isDone()) task.cancel(true);
        }
        documentTasks.removeIf(Future::isDone);
    }

    private void cancelOutstandingRenders() {
        for (Future<?> task : List.copyOf(renderTasks)) {
            if (!task.isDone()) task.cancel(true);
        }
        renderTasks.removeIf(Future::isDone);
    }

    private void cancelSingleRender() {
        Future<?> task = singleRenderTask;
        singleRenderTask = null;
        if (task != null && !task.isDone()) task.cancel(true);
    }

    public void closeDocument() {
        generation.incrementAndGet();
        singleRenderGeneration.incrementAndGet();
        cancelOutstandingRenders();
        cancelOutstandingDocumentTasks();
        cancelSingleRender();
        PdfDocumentSession current = session;
        session = null;
        progress.setVisible(false); progress.setManaged(false);
        singleImage.setImage(null);
        continuousList.getItems().clear();
        thumbnails.getItems().clear();
        pageIndex = 0;
        updateState();
        if (current != null) closeSessionAfterPendingRenders(current);
    }

    private void closeSessionAfterPendingRenders(PdfDocumentSession current) {
        try {
            renderExecutor.submit(() -> {
                try {
                    current.close();
                } catch (IOException error) {
                    if (!closed) Platform.runLater(() -> onError.accept(error.getMessage()));
                }
            });
        } catch (RejectedExecutionException executorClosed) {
            try { current.close(); }
            catch (IOException ignored) { }
        }
    }

    private String t(String key) {
        String value = text.apply(key);
        return value == null || value.isBlank() ? key : value;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeDocument();
        renderExecutor.shutdown();
    }

    private enum FitMode { NONE, WIDTH, PAGE }
}
