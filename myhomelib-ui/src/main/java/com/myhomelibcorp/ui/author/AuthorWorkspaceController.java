package com.myhomelibcorp.ui.author;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.author.LoadAuthorByIdUseCase;
import com.myhomelibcorp.application.usecase.book.LoadBooksByAuthorUseCase;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.NavigationService;
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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthorWorkspaceController {

    private final LoadAuthorByIdUseCase loadAuthorByIdUseCase;
    private final LoadBooksByAuthorUseCase loadBooksByAuthorUseCase;
    private final NavigationService navigationService;
    private final CoverPresenter coverPresenter;
    private final ApplicationState appState;
        private final BookViewModelMapper bookViewModelMapper;

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
        titleColumn.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        seriesColumn.setCellValueFactory(cellData -> cellData.getValue().seriesProperty());
        seqNumberColumn.setCellValueFactory(cellData -> cellData.getValue().sequenceNumberProperty());
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
        this.currentAuthorId = authorId;
        loadAuthorData(authorId);
    }

    private void loadAuthorData(AuthorId authorId) {
        loadAuthorByIdUseCase.execute(authorId).ifPresentOrElse(author -> {
            this.currentAuthor = author;
            UiExecutor.runOnUiThread(() -> updateAuthorUI(author));
        }, () -> {
            log.warn("Author not found: {}", authorId);
        });

        List<BookListItem> items = loadBooksByAuthorUseCase.execute(authorId, 1000, 0);
        this.allBooks = items.stream()
                .map(item -> {
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
                    return dto;
                })
                .collect(Collectors.toList());
        UiExecutor.runOnUiThread(() -> updateBooksUI(allBooks));
    }

    private void updateAuthorUI(AuthorDto author) {
        authorNameLabel.setText(author.getFullName());
        authorFullNameLabel.setText(author.getFullName());
        booksCountLabel.setText("Книг: " + (allBooks != null ? allBooks.size() : 0));
        seriesCountLabel.setText("Серій: 0");
        genresCountLabel.setText("Жанрів: 0");
        bioLabel.setText("Біографія автора...");
    }

    private void updateBooksUI(List<BookDto> books) {
        List<BookViewModel> vms = books.stream()
                .map(bookViewModelMapper::toViewModel)
                .collect(Collectors.toList());
        booksTableView.getItems().setAll(vms);
        booksCountLabel.setText("Книг: " + vms.size());
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
                .filter(book -> book.getTitle().toLowerCase().contains(lowerQuery)
                        || (book.getSeries() != null && book.getSeries().toLowerCase().contains(lowerQuery)))
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
}