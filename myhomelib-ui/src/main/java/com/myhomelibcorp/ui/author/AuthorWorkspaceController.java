package com.myhomelibcorp.ui.author;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.usecase.author.LoadAuthorByIdUseCase;
import com.myhomelibcorp.application.usecase.author.UpdateAuthorDescriptionUseCase;
import com.myhomelibcorp.application.usecase.book.LoadBooksByAuthorUseCase;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
@Slf4j
public class AuthorWorkspaceController {

    private final LoadAuthorByIdUseCase loadAuthorByIdUseCase;
    private final UpdateAuthorDescriptionUseCase updateAuthorDescriptionUseCase;
    private final LoadBooksByAuthorUseCase loadBooksByAuthorUseCase;
    private final NavigationService navigationService;
    private final CoverPresenter coverPresenter;
    private final ApplicationState appState;
    private final BookViewModelMapper bookViewModelMapper;
    private final UiBackgroundExecutor executor;

    @FXML private Label authorNameLabel;
    @FXML private Label authorFullNameLabel;
    @FXML private ImageView authorPhotoImageView;
    @FXML private Label booksCountLabel;
    @FXML private Label seriesCountLabel;
    @FXML private Label genresCountLabel;
    @FXML private TextArea bioLabel;
    @FXML private TableView<BookViewModel> booksTableView;
    @FXML private TableColumn<BookViewModel, String> titleColumn;
    @FXML private TableColumn<BookViewModel, String> seriesColumn;
    @FXML private TableColumn<BookViewModel, Number> seqNumberColumn;
    @FXML private TableColumn<BookViewModel, String> yearColumn;
    @FXML private TableColumn<BookViewModel, String> formatColumn;
    @FXML private TableColumn<BookViewModel, String> rateColumn;
    @FXML private TableColumn<BookViewModel, String> progressColumn;
    @FXML private TextField filterTextField;
    @FXML private TextField searchField;

    private AuthorId currentAuthorId;
    private AuthorDto currentAuthor;
    private List<BookDto> allBooks;

    @FXML
    public void initialize() {
        log.info("AuthorWorkspaceController.initialize() викликано");

        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        seqNumberColumn.setCellValueFactory(cellData -> cellData.getValue().sequenceNumberProperty());
        // Використовуємо createdAtFormattedProperty, оскільки yearProperty відсутній
        yearColumn.setCellValueFactory(cellData -> cellData.getValue().createdAtFormattedProperty());
        formatColumn.setCellValueFactory(cellData -> cellData.getValue().localStatusProperty());
        rateColumn.setCellValueFactory(cellData -> cellData.getValue().rateStarsProperty());
        progressColumn.setCellValueFactory(cellData -> cellData.getValue().progressFormattedProperty());

        booksTableView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                appState.getBookDetails().setCurrentBook(
                        bookViewModelMapper.toDto(selected)
                );
            }
        });

        booksTableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigationService.navigateToBook(BookId.fromString(selected.getId()));
                }
            }
        });

        filterTextField.textProperty().addListener((obs, old, query) -> {
            filterBooks(query);
        });
    }

    public void setAuthorId(AuthorId authorId) {
        if (authorId == null) {
            throw new IllegalArgumentException("AuthorId не може бути null");
        }

        this.currentAuthorId = authorId;
        log.info("Встановлено автора для workspace: {}", authorId);

        loadAuthorData(authorId);
    }

    private void loadAuthorData(AuthorId authorId) {
        log.info("Завантаження workspace автора: {}", authorId);

        executor.submit(() -> {
            AuthorDto author = loadAuthorByIdUseCase.execute(authorId)
                    .orElseThrow(() -> new IllegalStateException("Автор не знайдений: " + authorId));

            List<BookListItem> items = loadBooksByAuthorUseCase.execute(authorId, 1000, 0);
            List<BookDto> books = items.stream()
                    .map(this::toBookDto)
                    .collect(Collectors.toList());

            return new AuthorWorkspaceData(author, books);

        }).thenAccept(data -> {
            this.currentAuthor = data.author();
            this.allBooks = data.books();

            UiExecutor.runOnUiThread(() -> {
                updateAuthorUI(data.author());
                updateBooksUI(data.books());
            });

        }).exceptionally(ex -> {
            log.error("Помилка завантаження автора {}", authorId, ex);

            UiExecutor.runOnUiThread(() -> {
                booksTableView.getItems().clear();
                booksCountLabel.setText("Книг: 0");
                seriesCountLabel.setText("Серій: 0");
                genresCountLabel.setText("Жанрів: 0");
            });

            return null;
        });
    }

    private record AuthorWorkspaceData(AuthorDto author, List<BookDto> books) {}

    private BookDto toBookDto(BookListItem item) {
        BookDto dto = new BookDto();
        dto.setId(item.getId());
        dto.setTitle(item.getTitle());
        dto.setAuthorsText(item.getAuthorsText());
        dto.setSeries(item.getSeries());
        dto.setGenresText(item.getGenresText());
        dto.setRate(item.getRate());
        dto.setProgress(item.getProgress());
        dto.setFileSize(item.getFileSize());
        dto.setLanguage(item.getLanguage());
        dto.setFileName(item.getFileName());
        dto.setFolder(item.getFolder());
        dto.setCollectionRoot(item.getCollectionRoot());
        dto.setAnnotation(item.getAnnotation());
        dto.setYear(0); // Тимчасово
        return dto;
    }

    private void updateAuthorUI(AuthorDto author) {
        authorNameLabel.setText(author.getFullName());
        authorFullNameLabel.setText(author.getFullName());
        bioLabel.setText(author.getAnnotation() == null ? "" : author.getAnnotation());
    }

    private void updateBooksUI(List<BookDto> books) {
        if (booksTableView == null) return;

        List<BookViewModel> vms = books.stream()
                .map(bookViewModelMapper::toViewModel)
                .collect(Collectors.toList());
        booksTableView.getItems().setAll(vms);

        booksCountLabel.setText("Книг: " + books.size());

        long seriesCount = books.stream()
                .map(BookDto::getSeries)
                .filter(series -> series != null && !series.isBlank())
                .distinct()
                .count();
        seriesCountLabel.setText("Серій: " + seriesCount);

        long genresCount = books.stream()
                .flatMap(book -> {
                    if (book.getGenresText() == null || book.getGenresText().isBlank()) {
                        return java.util.stream.Stream.empty();
                    }
                    return java.util.Arrays.stream(book.getGenresText().split(","));
                })
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        genresCountLabel.setText("Жанрів: " + genresCount);
    }

    private void filterBooks(String query) {
        if (query == null || query.isBlank()) {
            if (allBooks != null) {
                updateBooksUI(allBooks);
            }
            return;
        }
        String lowerQuery = query.toLowerCase();
        List<BookDto> filtered = allBooks.stream()
                .filter(book -> {
                    String title = book.getTitle();
                    String series = book.getSeries();
                    return (title != null && title.toLowerCase().contains(lowerQuery)) ||
                            (series != null && series.toLowerCase().contains(lowerQuery));
                })
                .collect(Collectors.toList());
        updateBooksUI(filtered);
    }

    @FXML
    private void onSortByTitle() {
        if (allBooks != null) {
            allBooks.sort((b1, b2) -> b1.getTitle().compareToIgnoreCase(b2.getTitle()));
            updateBooksUI(allBooks);
        }
    }

    @FXML
    private void onSortByYear() {
        if (allBooks != null) {
            allBooks.sort((b1, b2) -> {
                int y1 = b1.getYear() != null ? b1.getYear() : 0;
                int y2 = b2.getYear() != null ? b2.getYear() : 0;
                return Integer.compare(y1, y2);
            });
            updateBooksUI(allBooks);
        }
    }

    @FXML
    private void onSortByRating() {
        if (allBooks != null) {
            allBooks.sort((b1, b2) -> Integer.compare(b2.getRate(), b1.getRate()));
            updateBooksUI(allBooks);
        }
    }

    @FXML
    private void onOpenBook() {
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            navigationService.navigateToBook(BookId.fromString(selected.getId()));
        }
    }

    @FXML
    private void onReadBook() {
        BookViewModel selected = booksTableView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            BookDto book = new BookDto();
            book.setId(selected.getId());
            book.setTitle(selected.getTitle());
            book.setAuthorsText(selected.getAuthorsText());
            book.setSeries(selected.getSeries());
            book.setGenresText(selected.getGenresText());
            book.setRate(selected.getRate());
            book.setProgress(selected.getProgress());
            book.setFileName(selected.getFileName());
            book.setFolder(selected.getFolder());
            book.setArchiveEntry(selected.getArchiveEntry());
            book.setCollectionRoot(selected.getCollectionRoot());
            book.setAnnotation(selected.getAnnotation());
            book.setLanguage(selected.getLanguage());
            navigationService.readBook(book);
        }
    }
    @FXML
    private void onEditAuthorDescription() {
        if (currentAuthorId == null || currentAuthor == null) return;
        TextArea area = new TextArea(currentAuthor.getAnnotation() == null ? "" : currentAuthor.getAnnotation());
        area.setWrapText(true); area.setPrefRowCount(14);
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> d = new javafx.scene.control.Dialog<>();
        d.setTitle("Опис автора"); d.setHeaderText(currentAuthor.getFullName());
        d.getDialogPane().setContent(area); d.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        if (d.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == javafx.scene.control.ButtonType.OK) {
            updateAuthorDescriptionUseCase.execute(currentAuthorId, area.getText());
            currentAuthor.setAnnotation(area.getText()); bioLabel.setText(area.getText());
        }
    }

}