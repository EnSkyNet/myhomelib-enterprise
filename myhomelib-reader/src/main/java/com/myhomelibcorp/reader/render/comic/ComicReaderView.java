package com.myhomelibcorp.reader.render.comic;

import com.myhomelibcorp.reader.api.ReaderPosition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** JavaFX CBZ/CBR reader with lazy pages, spread mode, manga RTL and thumbnails. */
public final class ComicReaderView extends BorderPane implements AutoCloseable {
    private final Function<String, String> text;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "comic-reader-render");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong singleRenderGeneration = new AtomicLong();
    private final java.util.Set<Future<?>> renderTasks = ConcurrentHashMap.newKeySet();

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
    private final ToggleButton dualPage = new ToggleButton();
    private final ToggleButton rtl = new ToggleButton();
    private final ToggleButton thumbnailsToggle = new ToggleButton();
    private final Button addBookmark = new Button();
    private final Button bookmarks = new Button();
    private final ProgressIndicator progress = new ProgressIndicator();

    private final ImageView firstImage = imageView();
    private final ImageView secondImage = imageView();
    private final HBox spread = new HBox(12, firstImage, secondImage);
    private final StackPane singlePane = new StackPane(spread);
    private final ScrollPane singleScroll = new ScrollPane(singlePane);
    private final ListView<Integer> continuousList = new ListView<>();
    private final ListView<Integer> thumbnails = new ListView<>();

    private ComicDocumentSession session;
    private int pageIndex;
    private double zoom = 1.0;
    private FitMode fitMode = FitMode.PAGE;
    private boolean dualPageMode;
    private boolean rtlMode;
    private Future<?> singleRenderTask;
    private Consumer<ReaderPosition> onPositionChanged = ignored -> { };
    private Runnable onBack = () -> { };
    private Runnable onAddBookmark = () -> { };
    private Runnable onBookmarks = () -> { };
    private Consumer<String> onError = ignored -> { };
    private volatile boolean closed;

    public ComicReaderView(Function<String, String> text) {
        this.text = text == null ? Function.identity() : text;
        buildUi();
    }

    private void buildUi() {
        back.setText(t("ui.reader.back"));
        fitWidth.setText(t("ui.reader.comic.fit_width"));
        fitPage.setText(t("ui.reader.comic.fit_page"));
        continuous.setText(t("ui.reader.comic.continuous"));
        dualPage.setText(t("ui.reader.comic.dual_page"));
        rtl.setText(t("ui.reader.comic.rtl"));
        thumbnailsToggle.setText(t("ui.reader.comic.thumbnails"));
        addBookmark.setText(t("ui.reader.toolbar.add_bookmark"));
        bookmarks.setText(t("ui.reader.toolbar.bookmarks"));
        progress.setPrefSize(22, 22);
        progress.setVisible(false);
        progress.setManaged(false);

        ToggleGroup fitGroup = new ToggleGroup();
        fitWidth.setToggleGroup(fitGroup);
        fitPage.setToggleGroup(fitGroup);
        fitPage.setSelected(true);
        fitGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) fitMode = FitMode.NONE;
            else if (now == fitWidth) fitMode = FitMode.WIDTH;
            else fitMode = FitMode.PAGE;
            rerenderVisible();
        });

        HBox bar = new HBox(6, back, previous, next, pageLabel, zoomOut, zoomIn, zoomReset,
                fitWidth, fitPage, continuous, dualPage, rtl, thumbnailsToggle,
                addBookmark, bookmarks, progress);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));
        setTop(bar);

        spread.setAlignment(Pos.TOP_CENTER);
        singlePane.setAlignment(Pos.TOP_CENTER);
        singlePane.setPadding(new Insets(12));
        singleScroll.setFitToWidth(true);
        singleScroll.setFitToHeight(true);
        setCenter(singleScroll);

        continuousList.setCellFactory(spreadCellFactory());
        thumbnails.setCellFactory(thumbnailCellFactory());
        thumbnails.setPrefWidth(160);
        thumbnails.setVisible(false);
        thumbnails.setManaged(false);
        setLeft(thumbnails);

        back.setOnAction(e -> onBack.run());
        previous.setOnAction(e -> goRelative(-1));
        next.setOnAction(e -> goRelative(1));
        zoomOut.setOnAction(e -> setZoom(zoom / 1.2));
        zoomIn.setOnAction(e -> setZoom(zoom * 1.2));
        zoomReset.setOnAction(e -> { fitGroup.selectToggle(null); setZoom(1.0); });
        continuous.setOnAction(e -> switchContinuous(continuous.isSelected()));
        dualPage.setOnAction(e -> {
            dualPageMode = dualPage.isSelected();
            rebuildPageItems();
            rerenderVisible();
            updateState();
        });
        rtl.setOnAction(e -> {
            rtlMode = rtl.isSelected();
            rerenderVisible();
        });
        thumbnailsToggle.setOnAction(e -> {
            boolean show = thumbnailsToggle.isSelected();
            thumbnails.setVisible(show);
            thumbnails.setManaged(show);
            rerenderVisible();
        });
        addBookmark.setOnAction(e -> onAddBookmark.run());
        bookmarks.setOnAction(e -> onBookmarks.run());

        thumbnails.getSelectionModel().selectedItemProperty().addListener((obs, old, page) -> {
            if (page != null && page != pageIndex) goToPage(page);
        });
        continuousList.getSelectionModel().selectedItemProperty().addListener((obs, old, spreadStart) -> {
            if (spreadStart != null && continuous.isSelected()) setCurrentPage(spreadStart, true);
        });
        continuousList.setOnScroll(event -> Platform.runLater(this::syncContinuousPageFromViewport));
        continuousList.setOnMouseReleased(event -> Platform.runLater(this::syncContinuousPageFromViewport));

        ChangeListener<Number> sizeListener = (obs, old, value) -> {
            if (fitMode != FitMode.NONE) rerenderVisible();
        };
        singleScroll.viewportBoundsProperty().addListener((obs, old, value) -> {
            if (fitMode != FitMode.NONE) rerenderVisible();
        });
        widthProperty().addListener(sizeListener);
        heightProperty().addListener(sizeListener);
    }

    public void openPrepared(ComicDocumentSession prepared, ReaderPosition savedPosition) {
        Objects.requireNonNull(prepared, "prepared");
        if (closed) {
            prepared.close();
            throw new IllegalStateException("Comic Reader view is closed");
        }
        closeDocument();
        session = prepared;
        pageIndex = ComicPagePosition.restorePageIndex(savedPosition, prepared.pageCount());
        rebuildPageItems();
        updateState();
        renderSingleSpread();
        Platform.runLater(() -> thumbnails.scrollTo(pageIndex));
    }

    public boolean isOpen() { return session != null; }
    public int pageCount() { return session == null ? 0 : session.pageCount(); }
    public int currentPageIndex() { return pageIndex; }
    public ReaderPosition currentPosition() { return ComicPagePosition.toReaderPosition(pageIndex); }
    public double progressPercent() { return ComicPagePosition.progressPercent(pageIndex, pageCount()); }

    public void goToPosition(ReaderPosition position) {
        ComicDocumentSession current = session;
        if (current == null) return;
        goToPage(ComicPagePosition.restorePageIndex(position, current.pageCount()));
    }

    public void goToPage(int requested) {
        int count = pageCount();
        if (count <= 0) return;
        int safe = Math.max(0, Math.min(count - 1, requested));
        setCurrentPage(safe, true);
        int start = spreadStart(safe);
        if (continuous.isSelected()) {
            continuousList.getSelectionModel().select(Integer.valueOf(start));
            continuousList.scrollTo(startItemIndex(start));
        } else {
            renderSingleSpread();
        }
        thumbnails.getSelectionModel().select(Integer.valueOf(safe));
        thumbnails.scrollTo(safe);
    }

    public void setOnPositionChanged(Consumer<ReaderPosition> callback) {
        onPositionChanged = callback == null ? ignored -> { } : callback;
    }
    public void setOnBack(Runnable callback) { onBack = callback == null ? () -> { } : callback; }
    public void setOnAddBookmark(Runnable callback) { onAddBookmark = callback == null ? () -> { } : callback; }
    public void setOnBookmarks(Runnable callback) { onBookmarks = callback == null ? () -> { } : callback; }
    public void setOnError(Consumer<String> callback) { onError = callback == null ? ignored -> { } : callback; }

    private void goRelative(int direction) {
        int count = pageCount();
        if (count <= 0) return;
        int step = dualPageMode ? 2 : 1;
        int base = spreadStart(pageIndex);
        int target = direction < 0 ? base - step : base + step;
        goToPage(Math.max(0, Math.min(count - 1, target)));
    }

    private void setCurrentPage(int page, boolean notify) {
        int count = pageCount();
        if (count <= 0) return;
        int safe = Math.max(0, Math.min(count - 1, page));
        boolean changed = safe != pageIndex;
        pageIndex = safe;
        updateState();
        if ((changed || notify) && session != null) onPositionChanged.accept(currentPosition());
    }

    private void setZoom(double value) {
        zoom = Math.max(0.25, Math.min(4.0, value));
        updateState();
        rerenderVisible();
    }

    private void switchContinuous(boolean enabled) {
        generation.incrementAndGet();
        cancelOutstandingRenders();
        cancelSingleRender();
        rebuildPageItems();
        if (enabled) {
            setCenter(continuousList);
            int start = spreadStart(pageIndex);
            continuousList.getSelectionModel().select(Integer.valueOf(start));
            Platform.runLater(() -> continuousList.scrollTo(startItemIndex(start)));
        } else {
            setCenter(singleScroll);
            renderSingleSpread();
        }
    }

    private void rebuildPageItems() {
        ComicDocumentSession current = session;
        if (current == null) {
            continuousList.getItems().clear();
            thumbnails.getItems().clear();
            return;
        }
        List<Integer> spreads = new ArrayList<>();
        int step = dualPageMode ? 2 : 1;
        for (int page = 0; page < current.pageCount(); page += step) spreads.add(page);
        continuousList.setItems(FXCollections.observableArrayList(spreads));
        List<Integer> pages = new ArrayList<>(current.pageCount());
        for (int page = 0; page < current.pageCount(); page++) pages.add(page);
        thumbnails.setItems(FXCollections.observableArrayList(pages));
    }

    private int spreadStart(int page) {
        return dualPageMode ? Math.max(0, page - Math.floorMod(page, 2)) : Math.max(0, page);
    }

    private int startItemIndex(int spreadStart) {
        return dualPageMode ? spreadStart / 2 : spreadStart;
    }

    private List<Integer> spreadPages(int start) {
        int count = pageCount();
        if (start < 0 || start >= count) return List.of();
        if (!dualPageMode || start + 1 >= count) return List.of(start);
        return List.of(start, start + 1);
    }

    private void syncContinuousPageFromViewport() {
        if (session == null || !continuous.isSelected() || continuousList.getScene() == null) return;
        Bounds viewport = continuousList.localToScene(continuousList.getBoundsInLocal());
        Integer visibleStart = null;
        double nearestTop = Double.POSITIVE_INFINITY;
        for (Node node : continuousList.lookupAll(".list-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.isEmpty() || !(cell.getItem() instanceof Integer start)) continue;
            Bounds bounds = cell.localToScene(cell.getBoundsInLocal());
            if (bounds.getMaxY() <= viewport.getMinY() || bounds.getMinY() >= viewport.getMaxY()) continue;
            double top = Math.max(bounds.getMinY(), viewport.getMinY());
            if (top < nearestTop) {
                nearestTop = top;
                visibleStart = start;
            }
        }
        if (visibleStart != null) setCurrentPage(visibleStart, true);
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
            renderSingleSpread();
            thumbnails.refresh();
        }
    }

    private void renderSingleSpread() {
        ComicDocumentSession current = session;
        if (current == null || continuous.isSelected()) return;
        cancelSingleRender();
        long configToken = generation.get();
        long token = singleRenderGeneration.incrementAndGet();
        int start = spreadStart(pageIndex);
        List<Integer> pages = spreadPages(start);
        progress.setVisible(true);
        progress.setManaged(true);
        singleRenderTask = submitTrackedRender(() -> {
            try {
                List<ComicRenderedPage> rendered = renderPages(current, pages, false);
                Platform.runLater(() -> {
                    if (session != current || configToken != generation.get() || token != singleRenderGeneration.get()
                            || spreadStart(pageIndex) != start) return;
                    applySpread(spread, firstImage, secondImage, rendered, false);
                    progress.setVisible(false);
                    progress.setManaged(false);
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (session == current && configToken == generation.get() && token == singleRenderGeneration.get()) {
                        progress.setVisible(false);
                        progress.setManaged(false);
                        onError.accept(message(error));
                    }
                });
            }
        });
    }

    private Callback<ListView<Integer>, ListCell<Integer>> thumbnailCellFactory() {
        return list -> new ListCell<>() {
            private final ImageView image = imageView();
            private Future<?> task;
            private int requested = -1;
            {
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
                ComicDocumentSession current = session;
                long token = generation.get();
                task = submitTrackedRender(() -> {
                    try {
                        ComicRenderedPage rendered = current.render(page, 128, 180);
                        Platform.runLater(() -> {
                            if (session != current || token != generation.get() || requested != page
                                    || !Objects.equals(getItem(), page)) return;
                            image.setImage(toFxImage(rendered));
                            image.setFitWidth(128);
                            image.setFitHeight(180);
                        });
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (Exception error) {
                        Platform.runLater(() -> {
                            if (session == current && token == generation.get() && requested == page) {
                                setGraphic(null);
                                setContentDisplay(ContentDisplay.TEXT_ONLY);
                                setText(t("ui.reader.comic.render_error"));
                            }
                        });
                    }
                });
            }
        };
    }

    private Callback<ListView<Integer>, ListCell<Integer>> spreadCellFactory() {
        return list -> new ListCell<>() {
            private final ImageView first = imageView();
            private final ImageView second = imageView();
            private final HBox box = new HBox(12, first, second);
            private Future<?> task;
            private int requestedStart = -1;
            {
                box.setAlignment(Pos.TOP_CENTER);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setGraphic(box);
            }
            @Override protected void updateItem(Integer start, boolean empty) {
                super.updateItem(start, empty);
                if (task != null) task.cancel(true);
                requestedStart = start == null ? -1 : start;
                first.setImage(null);
                second.setImage(null);
                if (empty || start == null || session == null) { setGraphic(null); return; }
                setGraphic(box);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                ComicDocumentSession current = session;
                long token = generation.get();
                List<Integer> pages = spreadPages(start);
                task = submitTrackedRender(() -> {
                    try {
                        List<ComicRenderedPage> rendered = renderPages(current, pages, false);
                        Platform.runLater(() -> {
                            if (session != current || token != generation.get() || requestedStart != start
                                    || !Objects.equals(getItem(), start)) return;
                            applySpread(box, first, second, rendered, false);
                        });
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (Exception error) {
                        Platform.runLater(() -> {
                            if (session == current && token == generation.get() && requestedStart == start) {
                                setGraphic(null);
                                setContentDisplay(ContentDisplay.TEXT_ONLY);
                                setText(t("ui.reader.comic.render_error"));
                            }
                        });
                    }
                });
            }
        };
    }

    private List<ComicRenderedPage> renderPages(ComicDocumentSession current, List<Integer> pages, boolean thumbnail)
            throws IOException, InterruptedException {
        int[] target = renderTarget(thumbnail);
        List<ComicRenderedPage> result = new ArrayList<>(pages.size());
        for (Integer page : pages) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Comic render cancelled");
            result.add(current.render(page, target[0], target[1]));
        }
        return result;
    }

    private int[] renderTarget(boolean thumbnail) {
        if (thumbnail) return new int[]{128, 180};
        if (fitMode == FitMode.NONE) return new int[]{0, 0};
        double availableWidth = Math.max(200.0, getWidth() - (thumbnails.isManaged() ? thumbnails.getWidth() : 0.0) - 48.0);
        if (dualPageMode) availableWidth = Math.max(100.0, (availableWidth - 12.0) / 2.0);
        double availableHeight = Math.max(200.0, getHeight() - 100.0);
        if (fitMode == FitMode.WIDTH) return new int[]{(int) Math.ceil(availableWidth), 0};
        return new int[]{(int) Math.ceil(availableWidth), (int) Math.ceil(availableHeight)};
    }

    private void applySpread(HBox box, ImageView first, ImageView second, List<ComicRenderedPage> rendered, boolean thumbnail) {
        first.setImage(null);
        second.setImage(null);
        if (rendered.isEmpty()) {
            box.getChildren().clear();
            return;
        }
        first.setImage(toFxImage(rendered.getFirst()));
        configureDisplay(first, rendered.getFirst(), thumbnail);
        if (rendered.size() > 1) {
            second.setImage(toFxImage(rendered.get(1)));
            configureDisplay(second, rendered.get(1), thumbnail);
            if (rtlMode) box.getChildren().setAll(second, first);
            else box.getChildren().setAll(first, second);
        } else {
            box.getChildren().setAll(first);
        }
    }

    private void configureDisplay(ImageView image, ComicRenderedPage rendered, boolean thumbnail) {
        image.setPreserveRatio(true);
        if (thumbnail) {
            image.setFitWidth(128);
            image.setFitHeight(180);
            return;
        }
        double availableWidth = Math.max(200.0, getWidth() - (thumbnails.isManaged() ? thumbnails.getWidth() : 0.0) - 48.0);
        if (dualPageMode) availableWidth = Math.max(100.0, (availableWidth - 12.0) / 2.0);
        double availableHeight = Math.max(200.0, getHeight() - 100.0);
        if (fitMode == FitMode.WIDTH) {
            image.setFitWidth(availableWidth);
            image.setFitHeight(0);
        } else if (fitMode == FitMode.PAGE) {
            image.setFitWidth(availableWidth);
            image.setFitHeight(availableHeight);
        } else {
            image.setFitWidth(rendered.width() * zoom);
            image.setFitHeight(rendered.height() * zoom);
        }
    }

    private void updateState() {
        int count = pageCount();
        if (count == 0) pageLabel.setText("0 / 0");
        else if (dualPageMode) {
            int start = spreadStart(pageIndex);
            int end = Math.min(count - 1, start + 1);
            pageLabel.setText(start == end ? (start + 1) + " / " + count : (start + 1) + "–" + (end + 1) + " / " + count);
        } else pageLabel.setText((pageIndex + 1) + " / " + count);
        previous.setDisable(count == 0 || spreadStart(pageIndex) <= 0);
        next.setDisable(count == 0 || spreadStart(pageIndex) + (dualPageMode ? 2 : 1) >= count);
        zoomReset.setText(Math.round(zoom * 100) + "%");
    }

    private Future<?> submitTrackedRender(Runnable action) {
        AtomicReference<Future<?>> handle = new AtomicReference<>();
        try {
            Future<?> submitted = renderExecutor.submit(() -> {
                try {
                    action.run();
                } finally {
                    Future<?> completed = handle.get();
                    if (completed != null) renderTasks.remove(completed);
                }
            });
            handle.set(submitted);
            renderTasks.add(submitted);
            if (submitted.isDone()) renderTasks.remove(submitted);
            return submitted;
        } catch (RejectedExecutionException error) {
            throw error;
        }
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
        cancelSingleRender();
        ComicDocumentSession current = session;
        session = null;
        progress.setVisible(false);
        progress.setManaged(false);
        firstImage.setImage(null);
        secondImage.setImage(null);
        continuousList.getItems().clear();
        thumbnails.getItems().clear();
        pageIndex = 0;
        updateState();
        if (current != null) closeSessionAfterPendingRenders(current);
    }

    private void closeSessionAfterPendingRenders(ComicDocumentSession current) {
        try {
            renderExecutor.submit(current::close);
        } catch (RejectedExecutionException executorClosed) {
            current.close();
        }
    }

    private String t(String key) {
        String value = text.apply(key);
        return value == null || value.isBlank() ? key : value;
    }

    private static ImageView imageView() {
        ImageView image = new ImageView();
        image.setPreserveRatio(true);
        image.setSmooth(true);
        return image;
    }

    private static WritableImage toFxImage(ComicRenderedPage rendered) {
        int width = rendered.width();
        int height = rendered.height();
        WritableImage image = new WritableImage(width, height);
        var writer = image.getPixelWriter();
        writer.setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), rendered.argb(), 0, width);
        return image;
    }

    private static String message(Throwable error) {
        if (error == null) return "";
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
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
