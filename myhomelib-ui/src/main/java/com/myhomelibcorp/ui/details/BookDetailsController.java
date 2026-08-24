package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
import com.myhomelibcorp.application.usecase.group.LoadBookGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookDetailsViewModel;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookDetailsController {

    private final ApplicationState appState;
    private final NavigationService navigationService;
    private final CoverPresenter coverPresenter;
    private final BookViewModelMapper viewModelMapper;
    private final MainController mainController;
    private final LoadBookGroupsUseCase loadBookGroupsUseCase;
    private final LocalizationService localizationService;

    @FXML private ImageView coverImageView;
    @FXML private Label titleLabel;
    @FXML private Label authorsLabel;
    @FXML private Label seriesLabel;
    @FXML private Label genresLabel;
    @FXML private Label languageLabel;
    @FXML private Label yearLabel;
    @FXML private Label publisherLabel;
    @FXML private Label isbnLabel;
    @FXML private FlowPane keywordsLinksPane;
    @FXML private FlowPane groupLinksPane;
    @FXML private FlowPane reviewLinksPane;
    @FXML private TextArea annotationArea;

    private ChangeListener<BookDto> bookChangeListener;

    @FXML
    public void initialize() {
        log.info("BookDetailsController.initialize() – прив'язка coverPresenter до coverImageView");
        coverPresenter.bind(coverImageView);

        BookDetailsViewModel vm = appState.getBookDetails();
        bookChangeListener = (obs, old, bookDto) -> {
            log.info("BookDetailsController: змінено книгу: old={}, new={}",
                    old != null ? old.getTitle() : "null",
                    bookDto != null ? bookDto.getTitle() : "null");

            coverPresenter.clearCover();
            if (bookDto != null) {
                updateUI(bookDto);
                var bookViewModel = viewModelMapper.toViewModel(bookDto);
                javafx.application.Platform.runLater(() -> coverPresenter.showCover(bookViewModel));
            } else {
                clearUI();
            }
        };

        vm.currentBookProperty().addListener(bookChangeListener);
        BookDto current = vm.getCurrentBook();
        if (current != null) {
            updateUI(current);
            coverPresenter.showCover(viewModelMapper.toViewModel(current));
        }
    }

    @PreDestroy
    public void cleanup() {
        BookDetailsViewModel vm = appState.getBookDetails();
        if (bookChangeListener != null) {
            vm.currentBookProperty().removeListener(bookChangeListener);
            bookChangeListener = null;
        }
    }

    private void updateUI(BookDto book) {
        titleLabel.setText(book.getTitle());
        authorsLabel.setText("Автори: " + book.getAuthorsText());
        seriesLabel.setText("Серія: " + (book.getSeries() != null ? book.getSeries() : "—"));
        genresLabel.setText("Жанр: " + book.getGenresText());
        languageLabel.setText("Мова: " + book.getLanguage());
        yearLabel.setText("Рік: " + (book.getYear() != null && book.getYear() > 0 ? String.valueOf(book.getYear()) : "—"));
        publisherLabel.setText("Видавництво: " + (book.getPublisher() != null ? book.getPublisher() : "—"));
        isbnLabel.setText("ISBN: " + (book.getIsbn() != null ? book.getIsbn() : "—"));
        annotationArea.setText(book.getAnnotation() != null ? book.getAnnotation() : "");

        renderKeywordLinks(book.getKeywords());
        renderGroupLinks(loadBookGroupsUseCase.execute(book.getId()));
        renderReviewLinks(book);
    }

    private void renderKeywordLinks(String rawKeywords) {
        keywordsLinksPane.getChildren().clear();
        List<String> keywords = splitKeywords(rawKeywords);
        if (keywords.isEmpty()) {
            keywordsLinksPane.getChildren().add(new Label("—"));
            return;
        }
        for (String keyword : keywords) {
            Hyperlink link = new Hyperlink(keyword);
            link.setOnAction(event -> navigationService.navigateToKeyword(keyword));
            keywordsLinksPane.getChildren().add(link);
        }
    }

    private void renderGroupLinks(List<GroupDto> groups) {
        groupLinksPane.getChildren().clear();
        if (groups == null || groups.isEmpty()) {
            groupLinksPane.getChildren().add(new Label("—"));
            return;
        }
        for (GroupDto group : groups) {
            if (group.getId() == null) continue;
            Hyperlink link = new Hyperlink(groupDisplayName(group.getName()));
            link.setOnAction(event -> navigationService.navigateToGroup(GroupId.fromLong(group.getId())));
            groupLinksPane.getChildren().add(link);
        }
        if (groupLinksPane.getChildren().isEmpty()) groupLinksPane.getChildren().add(new Label("—"));
    }

    private void renderReviewLinks(BookDto book) {
        reviewLinksPane.getChildren().clear();
        boolean rated = book.getRate() > 0;
        boolean reviewed = book.getReview() != null && !book.getReview().isBlank();
        if (rated) {
            Hyperlink ratedLink = new Hyperlink(localizationService.tr("Оцінені"));
            ratedLink.setOnAction(event -> navigationService.navigateToReviews(ReviewNavigationFilter.RATED));
            reviewLinksPane.getChildren().add(ratedLink);
        }
        if (reviewed) {
            Hyperlink reviewedLink = new Hyperlink(localizationService.tr("З відгуками"));
            reviewedLink.setOnAction(event -> navigationService.navigateToReviews(ReviewNavigationFilter.REVIEWED));
            reviewLinksPane.getChildren().add(reviewedLink);
        }
        if (rated && reviewed) {
            Hyperlink bothLink = new Hyperlink(localizationService.tr("Оцінені з відгуками"));
            bothLink.setOnAction(event -> navigationService.navigateToReviews(ReviewNavigationFilter.RATED_AND_REVIEWED));
            reviewLinksPane.getChildren().add(bothLink);
        }
        if (reviewLinksPane.getChildren().isEmpty()) reviewLinksPane.getChildren().add(new Label("—"));
    }

    static List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,;|]"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String groupDisplayName(String name) {
        if (name == null) return "";
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "favorites" -> localizationService.tr("Обране");
            case "to read" -> localizationService.tr("До читання");
            default -> name;
        };
    }

    private void clearUI() {
        titleLabel.setText("Назва");
        authorsLabel.setText("Автори");
        seriesLabel.setText("Серія");
        genresLabel.setText("Жанр");
        languageLabel.setText("Мова");
        yearLabel.setText("Рік");
        publisherLabel.setText("Видавництво");
        isbnLabel.setText("ISBN");
        annotationArea.setText("");
        keywordsLinksPane.getChildren().setAll(new Label("—"));
        groupLinksPane.getChildren().setAll(new Label("—"));
        reviewLinksPane.getChildren().setAll(new Label("—"));
    }

    @FXML
    private void onRead() {
        BookDto book = appState.getBookDetails().getCurrentBook();
        if (book != null) {
            mainController.cleanupReader();
            navigationService.readBook(book);
        }
    }

    @FXML
    private void onEdit() {
        BookDto book = appState.getBookDetails().getCurrentBook();
        if (book != null) log.info("Редагування книги: {}", book.getTitle());
    }

    @FXML
    private void onOpenFolder() {
        BookDto book = appState.getBookDetails().getCurrentBook();
        if (book != null) navigationService.openBookFolder(book);
    }
}
