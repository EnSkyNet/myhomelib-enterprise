package com.myhomelibcorp.ui.author;

import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import com.myhomelibcorp.application.dto.AuthorBookStatistics;
import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.application.usecase.author.LoadAuthorBookStatisticsUseCase;
import com.myhomelibcorp.application.usecase.author.LoadAuthorByIdUseCase;
import com.myhomelibcorp.application.usecase.author.UpdateAuthorDescriptionUseCase;
import com.myhomelibcorp.application.usecase.book.LoadBooksByAuthorUseCase;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.table.SeriesGrouping;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class AuthorWorkspaceController {

    private static final int PAGE_SIZE = 100;

    private final LoadAuthorByIdUseCase loadAuthorByIdUseCase;
    private final UpdateAuthorDescriptionUseCase updateAuthorDescriptionUseCase;
    private final LoadBooksByAuthorUseCase loadBooksByAuthorUseCase;
    private final LoadAuthorBookStatisticsUseCase loadAuthorBookStatisticsUseCase;
    private final CatalogUpdateService catalogUpdateService;
    private final NavigationService navigationService;
    private final ApplicationState appState;
    private final BookViewModelMapper bookViewModelMapper;
    private final UiBackgroundExecutor executor;

    @FXML private Label authorNameLabel;
    @FXML private Button followAuthorButton;
    @FXML private Label booksCountLabel;
    @FXML private Label seriesCountLabel;
    @FXML private Label genresCountLabel;
    @FXML private TextArea bioLabel;
    @FXML private TableView<BookViewModel> booksTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private TableColumn<BookViewModel, Number> seqNumberColumn;
    @FXML private TableColumn<BookViewModel, Number> yearColumn;
    @FXML private TableColumn<BookViewModel, String> formatColumn;
    @FXML private TableColumn<BookViewModel, String> fileSizeColumn;
    @FXML private TableColumn<BookViewModel, String> rateColumn;
    @FXML private TableColumn<BookViewModel, String> progressColumn;
    @FXML private TextField filterTextField;
    @FXML private Button previousPageButton;
    @FXML private Button nextPageButton;
    @FXML private Label pageLabel;

    private final AtomicLong metadataGeneration = new AtomicLong();
    private final AtomicLong pageGeneration = new AtomicLong();
    private PauseTransition filterDebounce;
    private AuthorId currentAuthorId;
    private AuthorDto currentAuthor;
    private AuthorBookStatistics authorStatistics = AuthorBookStatistics.empty();
    private boolean currentAuthorFollowed;
    private int currentPage;
    private SortBy currentSort = SortBy.SERIES;
    private SortDirection currentDirection = SortDirection.ASC;

    @FXML
    public void initialize() {
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        titleColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                BookViewModel row = getTableRow() != null ? getTableRow().getItem() : null;
                setText(row != null && !row.isGroupHeader() && row.getSeries() != null && !row.getSeries().isBlank()
                        ? "    " + item : item);
            }
        });
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        seqNumberColumn.setCellValueFactory(cellData -> cellData.getValue().sequenceNumberProperty());
        yearColumn.setCellValueFactory(cellData -> cellData.getValue().yearProperty());
        formatColumn.setCellValueFactory(cellData -> cellData.getValue().localStatusProperty());
        fileSizeColumn.setCellValueFactory(cellData -> cellData.getValue().fileSizeFormattedProperty());
        rateColumn.setCellValueFactory(cellData -> cellData.getValue().rateStarsProperty());
        progressColumn.setCellValueFactory(cellData -> cellData.getValue().progressFormattedProperty());

        booksTableView.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(BookViewModel item, boolean empty) {
                super.updateItem(item, empty);
                if (!empty && item != null && item.isGroupHeader()) {
                    setStyle("-fx-font-weight: bold; -fx-background-color: -fx-control-inner-background-alt;");
                    setMouseTransparent(true);
                } else {
                    setStyle("");
                    setMouseTransparent(false);
                }
            }
        });
        booksTableView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && !selected.isGroupHeader()) {
                appState.getBookDetails().setCurrentBook(bookViewModelMapper.toDto(selected));
            }
        });
        booksTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) onOpenBook();
        });

        filterDebounce = new PauseTransition(Duration.millis(300));
        filterDebounce.setOnFinished(event -> {
            currentPage = 0;
            reloadBooksPage();
        });
        filterTextField.textProperty().addListener((obs, old, value) -> filterDebounce.playFromStart());
        updatePageControls(PageResult.empty());
    }

    public void setAuthorId(AuthorId authorId) {
        if (authorId == null) throw new IllegalArgumentException("AuthorId не може бути null");
        currentAuthorId = authorId;
        currentPage = 0;
        currentSort = SortBy.SERIES;
        currentDirection = SortDirection.ASC;
        if (filterTextField != null) filterTextField.clear();
        loadAuthorData(authorId);
    }

    private void loadAuthorData(AuthorId authorId) {
        long generation = metadataGeneration.incrementAndGet();
        executor.submit(() -> {
            AuthorDto author = loadAuthorByIdUseCase.execute(authorId)
                    .orElseThrow(() -> new IllegalStateException("Автор не знайдений: " + authorId));
            AuthorBookStatistics statistics = loadAuthorBookStatisticsUseCase.execute(authorId);
            boolean followed = catalogUpdateService.isAuthorFollowed(authorId);
            return new AuthorWorkspaceMetadata(author, statistics, followed);
        }).thenAccept(data -> UiExecutor.runOnUiThread(() -> {
            if (generation != metadataGeneration.get() || !authorId.equals(currentAuthorId)) return;
            currentAuthor = data.author();
            authorStatistics = data.statistics();
            currentAuthorFollowed = data.followed();
            updateAuthorUI(data.author());
            updateFollowButton();
            updateStatisticsLabels();
            reloadBooksPage();
        })).exceptionally(ex -> {
            log.error("Помилка завантаження автора {}", authorId, ex);
            UiExecutor.runOnUiThread(() -> {
                if (generation != metadataGeneration.get()) return;
                booksTableView.getItems().clear();
                authorStatistics = AuthorBookStatistics.empty();
                updateStatisticsLabels();
                updatePageControls(PageResult.empty());
            });
            return null;
        });
    }

    private void reloadBooksPage() {
        AuthorId authorId = currentAuthorId;
        if (authorId == null) return;
        int requestedPage = Math.max(0, currentPage);
        int offset = requestedPage * PAGE_SIZE;
        String filter = filterTextField == null ? "" : filterTextField.getText();
        SortBy sort = currentSort;
        SortDirection direction = currentDirection;
        long generation = pageGeneration.incrementAndGet();
        setPagingBusy(true);

        executor.submit(() -> loadBooksByAuthorUseCase.execute(
                authorId, filter, sort, direction, PAGE_SIZE, offset
        )).thenAccept(page -> UiExecutor.runOnUiThread(() -> {
            if (generation != pageGeneration.get() || !authorId.equals(currentAuthorId)) return;
            if (page.content().isEmpty() && requestedPage > 0 && page.totalElements() > 0) {
                currentPage = Math.max(0, page.totalPages() - 1);
                reloadBooksPage();
                return;
            }
            currentPage = page.currentPage();
            var rows = page.content().stream().map(bookViewModelMapper::toViewModel).toList();
            booksTableView.getItems().setAll(currentSort == SortBy.SERIES
                    ? SeriesGrouping.groupPreservingOrder(rows)
                    : rows);
            updatePageControls(page);
            setPagingBusy(false);
        })).exceptionally(ex -> {
            log.error("Помилка завантаження книг автора {}", authorId, ex);
            UiExecutor.runOnUiThread(() -> {
                if (generation == pageGeneration.get()) setPagingBusy(false);
            });
            return null;
        });
    }

    private void updateStatisticsLabels() {
        booksCountLabel.setText("Книг: " + authorStatistics.books());
        seriesCountLabel.setText("Серій: " + authorStatistics.series());
        genresCountLabel.setText("Жанрів: " + authorStatistics.genres());
    }

    private void updatePageControls(PageResult<?> page) {
        int totalPages = Math.max(1, page.totalPages());
        int shownPage = page.totalElements() == 0 ? 0 : page.currentPage() + 1;
        String suffix = page.totalElements() == authorStatistics.books()
                ? ""
                : " · знайдено " + page.totalElements();
        pageLabel.setText("Сторінка " + shownPage + " / " + (page.totalElements() == 0 ? 0 : totalPages) + suffix);
        previousPageButton.setDisable(!page.hasPrevious());
        nextPageButton.setDisable(!page.hasNext());
    }

    private void setPagingBusy(boolean busy) {
        if (previousPageButton != null) previousPageButton.setDisable(busy || currentPage <= 0);
        if (nextPageButton != null && busy) nextPageButton.setDisable(true);
    }

    private record AuthorWorkspaceMetadata(AuthorDto author, AuthorBookStatistics statistics, boolean followed) {}

    private void updateAuthorUI(AuthorDto author) {
        authorNameLabel.setText(author.getFullName());
        bioLabel.setText(author.getAnnotation() == null ? "" : author.getAnnotation());
    }

    private void updateFollowButton() {
        if (followAuthorButton == null) return;
        followAuthorButton.setText(currentAuthorFollowed ? "Не стежити" : "Стежити за автором");
        followAuthorButton.setDisable(currentAuthorId == null);
    }

    @FXML
    private void onToggleAuthorFollowed() {
        AuthorId authorId = currentAuthorId;
        if (authorId == null || followAuthorButton == null) return;
        boolean target = !currentAuthorFollowed;
        followAuthorButton.setDisable(true);
        executor.submit(() -> {
            catalogUpdateService.setAuthorFollowed(authorId, target);
            return target;
        }).thenAccept(followed -> UiExecutor.runOnUiThread(() -> {
            if (!authorId.equals(currentAuthorId)) return;
            currentAuthorFollowed = followed;
            updateFollowButton();
        })).exceptionally(ex -> {
            log.error("Не вдалося змінити стеження за автором {}", authorId, ex);
            UiExecutor.runOnUiThread(this::updateFollowButton);
            return null;
        });
    }

    @FXML
    private void onPreviousPage() {
        if (currentPage <= 0) return;
        currentPage--;
        reloadBooksPage();
    }

    @FXML
    private void onNextPage() {
        currentPage++;
        reloadBooksPage();
    }

    @FXML
    private void onSortBySeries() {
        currentSort = SortBy.SERIES;
        currentDirection = SortDirection.ASC;
        currentPage = 0;
        reloadBooksPage();
    }

    @FXML
    private void onSortByTitle() {
        currentSort = SortBy.TITLE;
        currentDirection = SortDirection.ASC;
        currentPage = 0;
        reloadBooksPage();
    }

    @FXML
    private void onSortByYear() {
        currentSort = SortBy.YEAR;
        currentDirection = SortDirection.DESC;
        currentPage = 0;
        reloadBooksPage();
    }

    @FXML
    private void onSortByRating() {
        currentSort = SortBy.RATING;
        currentDirection = SortDirection.DESC;
        currentPage = 0;
        reloadBooksPage();
    }

    @FXML
    private void onOpenBook() {
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.isGroupHeader() && selected.getId() != null) {
            navigationService.navigateToBook(BookId.fromString(selected.getId()));
        }
    }

    @FXML
    private void onReadBook() {
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.isGroupHeader() && selected.getId() != null) {
            BookDto book = bookViewModelMapper.toDto(selected);
            navigationService.readBook(book);
        }
    }

    @FXML
    private void onEditAuthorDescription() {
        if (currentAuthorId == null || currentAuthor == null) return;
        TextArea area = new TextArea(currentAuthor.getAnnotation() == null ? "" : currentAuthor.getAnnotation());
        area.setWrapText(true);
        area.setPrefRowCount(14);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Опис автора");
        dialog.setHeaderText(currentAuthor.getFullName());
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            updateAuthorDescriptionUseCase.execute(currentAuthorId, area.getText());
            currentAuthor.setAnnotation(area.getText());
            bioLabel.setText(area.getText());
        }
    }
}
