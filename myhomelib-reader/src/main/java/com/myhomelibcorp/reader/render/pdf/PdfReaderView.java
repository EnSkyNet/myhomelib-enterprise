package com.myhomelibcorp.reader.render.pdf;

import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.render.api.PagedReaderPosition;
import com.myhomelibcorp.reader.render.javafx.ArgbFxImageSupport;
import com.myhomelibcorp.reader.render.support.PagedRenderCoordinator;
import com.myhomelibcorp.reader.render.support.TrackedTaskGroup;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.util.Callback;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/** JavaFX PDF renderer adapter with lazy background rasterization and bounded session cache. */
public final class PdfReaderView extends BorderPane implements AutoCloseable {
    private final Function<String, String> text;
    private final PagedRenderCoordinator renderCoordinator =
            new PagedRenderCoordinator("pdf-reader-render");
    private final TrackedTaskGroup documentTasks = renderCoordinator.newTaskGroup();

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
    private Consumer<ReaderPosition> onPositionChanged = ignored -> { };
    private Runnable onBack = () -> { };
    private Runnable onAddBookmark = () -> { };
    private Runnable onBookmarks = () -> { };
    private Runnable onToc = () -> { };
    private Runnable onSearch = () -> { };
    private Consumer<Throwable> onError = ignored -> { };
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
        int restored = PagedReaderPosition.restorePageIndex(savedPosition, prepared.pageCount());
        pageIndex = restored;
        updateState();
        renderSinglePage();
        Platform.runLater(() -> thumbnails.scrollTo(pageIndex));
    }

    public boolean isOpen() { return session != null; }
    public int pageCount() { return session == null ? 0 : session.pageCount(); }
    public int currentPageIndex() { return pageIndex; }
    public ReaderPosition currentPosition() { return PagedReaderPosition.toReaderPosition(pageIndex); }
    public double progressPercent() { return PagedReaderPosition.progressPercent(pageIndex, pageCount()); }
    public List<PdfOutlineEntry> outlineEntries() { return session == null ? List.of() : session.outlineEntries(); }

    public void goToPosition(ReaderPosition position) {
        if (session == null) return;
        goToPage(PagedReaderPosition.restorePageIndex(position, session.pageCount()));
    }

    public Future<?> searchTextAsync(String query, int maxResults, Consumer<PdfSearchOutcome> onSuccess,
                                     Consumer<Throwable> onFailure) {
        PdfDocumentSession current = session;
        if (current == null) return null;
        Consumer<PdfSearchOutcome> success = onSuccess == null ? ignored -> { } : onSuccess;
        Consumer<Throwable> failure = onFailure == null ? ignored -> { } : onFailure;
        long token = renderCoordinator.generation();
        return documentTasks.submit(() -> {
            try {
                PdfSearchOutcome outcome = current.searchText(query, maxResults);
                Platform.runLater(() -> {
                    if (session == current && renderCoordinator.isCurrent(token)) success.accept(outcome);
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (session == current && renderCoordinator.isCurrent(token)) {
                        failure.accept(error);
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
    public void setOnError(Consumer<Throwable> listener) { onError = listener == null ? ignored -> { } : listener; }

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
        renderCoordinator.invalidate();
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
        renderCoordinator.invalidate();
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
        PagedRenderCoordinator.SingleRenderToken token = renderCoordinator.beginSingleRender();
        int requestedPage = pageIndex;
        double scale = scaleFor(requestedPage, false);
        progress.setVisible(true); progress.setManaged(true);
        Future<?> task = renderCoordinator.submit(() -> {
            try {
                PdfRenderedPage rendered = current.render(requestedPage, scale);
                Platform.runLater(() -> {
                    if (session != current || !renderCoordinator.isCurrent(token)
                            || requestedPage != pageIndex) return;
                    singleImage.setImage(ArgbFxImageSupport.toWritableImage(rendered));
                    progress.setVisible(false); progress.setManaged(false);
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (session == current && renderCoordinator.isCurrent(token)) {
                        progress.setVisible(false); progress.setManaged(false);
                        onError.accept(error);
                    }
                });
            }
        });
        renderCoordinator.trackSingle(task);
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
                long token = renderCoordinator.generation();
                double scale = scaleFor(page, thumbnail);
                task = renderCoordinator.submit(() -> {
                    try {
                        PdfRenderedPage rendered = current.render(page, scale);
                        Platform.runLater(() -> {
                            if (session != current || !renderCoordinator.isCurrent(token) || requested != page || !Objects.equals(getItem(), page)) return;
                            image.setImage(ArgbFxImageSupport.toWritableImage(rendered));
                            if (thumbnail) image.setFitWidth(128);
                            else image.setFitWidth(0);
                        });
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } catch (Exception error) {
                        Platform.runLater(() -> {
                            if (session == current && renderCoordinator.isCurrent(token) && requested == page) {
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


    private void updateState() {
        int count = pageCount();
        pageLabel.setText(count == 0 ? "0 / 0" : (pageIndex + 1) + " / " + count);
        previous.setDisable(count == 0 || pageIndex <= 0);
        next.setDisable(count == 0 || pageIndex >= count - 1);
        zoomReset.setText(Math.round(zoom * 100) + "%");
    }

    public void closeDocument() {
        renderCoordinator.invalidate();
        documentTasks.cancelOutstanding();
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
        boolean queued = renderCoordinator.enqueueAfterPending(() -> {
            try {
                current.close();
            } catch (IOException error) {
                if (!closed) Platform.runLater(() -> onError.accept(error));
            }
        });
        if (!queued) {
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
        renderCoordinator.close();
    }

    private enum FitMode { NONE, WIDTH, PAGE }
}
