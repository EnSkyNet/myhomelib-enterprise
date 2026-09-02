package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.reader.inspection.DocumentImageInfo;
import com.myhomelibcorp.reader.inspection.DocumentInspection;
import com.myhomelibcorp.reader.inspection.TocPreviewEntry;
import com.myhomelibcorp.ui.details.model.RichBookDetailsSession;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookDetailsViewModel;
import jakarta.annotation.PreDestroy;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Pagination;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookDetailsController {

    private static final int MAX_GALLERY_IMAGE_BYTES = 24 * 1024 * 1024;

    private final ApplicationState appState;
    private final NavigationService navigationService;
    private final CoverPresenter coverPresenter;
    private final BookViewModelMapper viewModelMapper;
    private final LocalizationService localizationService;
    private final BookDetailsAnalysisService analysisService;
    private final UiBackgroundExecutor backgroundExecutor;

    @FXML private ImageView coverImageView;
    @FXML private Label titleLabel;
    @FXML private FlowPane authorsLinksPane;
    @FXML private FlowPane seriesLinksPane;
    @FXML private FlowPane genresLinksPane;
    @FXML private Label languageLabel;
    @FXML private Label sourceLanguageLabel;
    @FXML private Label yearLabel;
    @FXML private Hyperlink publisherLink;
    @FXML private Label isbnLabel;
    @FXML private Label translatorsLabel;
    @FXML private Label formatLabel;
    @FXML private Label fileSizeLabel;
    @FXML private Label textStatsLabel;
    @FXML private Label localStatusLabel;
    @FXML private Label ratingLabel;
    @FXML private Label progressLabel;
    @FXML private Label libraryRateLabel;
    @FXML private Label sourceUrlLabel;
    @FXML private Label documentWarningLabel;
    @FXML private FlowPane keywordsLinksPane;
    @FXML private FlowPane groupLinksPane;
    @FXML private FlowPane reviewLinksPane;
    @FXML private VBox tocPreviewBox;
    @FXML private Label imagesCountLabel;
    @FXML private Button showImagesButton;
    @FXML private Label annotationLabel;
    @FXML private TextArea reviewArea;

    private final AtomicLong loadGeneration = new AtomicLong();
    private ChangeListener<BookDto> bookChangeListener;
    private RichBookDetailsSession currentSession;

    @FXML
    public void initialize() {
        coverPresenter.bind(coverImageView);
        BookDetailsViewModel vm = appState.getBookDetails();
        bookChangeListener = (obs, old, bookDto) -> loadBookDetails(bookDto);
        vm.currentBookProperty().addListener(bookChangeListener);
        loadBookDetails(vm.getCurrentBook());
    }

    private void loadBookDetails(BookDto selected) {
        long generation = loadGeneration.incrementAndGet();
        closeCurrentSession();
        coverPresenter.clearCover();

        if (selected == null || selected.getId() == null || selected.getId().isBlank()) {
            clearUI();
            return;
        }

        showLoading(selected);
        backgroundExecutor.submit(() -> analysisService.analyze(selected.getId()))
                .thenAccept(session -> UiExecutor.runOnUiThread(() -> {
                    BookDto stillSelected = appState.getBookDetails().getCurrentBook();
                    boolean stale = generation != loadGeneration.get()
                            || stillSelected == null
                            || !selected.getId().equals(stillSelected.getId());
                    if (stale) {
                        session.close();
                        return;
                    }
                    closeCurrentSession();
                    currentSession = session;
                    updateUI(session);
                    coverPresenter.showCover(viewModelMapper.toViewModel(session.book()));
                }))
                .exceptionally(ex -> {
                    log.warn("Не вдалося завантажити rich details для {}: {}", selected.getId(), ex.getMessage());
                    UiExecutor.runOnUiThread(() -> {
                        if (generation == loadGeneration.get()) {
                            updateBasicUI(selected);
                            showWarning("Не вдалося завантажити повні відомості");
                        }
                    });
                    return null;
                });
    }

    private void updateUI(RichBookDetailsSession session) {
        BookDto book = session.book();
        DocumentInspection document = session.inspection();

        titleLabel.setText(value(book.getTitle(), "Без назви"));
        renderAuthorLinks(book.getAuthors(), book.getAuthorsText());
        renderSeriesLink(book.getSeries());
        renderGenreLinks(book.getGenreItems(), book.getGenresText());

        languageLabel.setText("Мова: " + value(firstNonBlank(book.getLanguage(), document.language()), "—"));
        sourceLanguageLabel.setText("Мова оригіналу: " + value(document.sourceLanguage(), "—"));
        yearLabel.setText("Рік: " + year(book, document));

        String publisher = firstNonBlank(book.getPublisher(), document.publisher());
        publisherLink.setText("Видавництво: " + value(publisher, "—"));
        publisherLink.setDisable(publisher.isBlank());
        publisherLink.setOnAction(event -> {
            if (!publisher.isBlank()) navigationService.navigateToPublisher(publisher);
        });

        isbnLabel.setText("ISBN: " + value(firstNonBlank(book.getIsbn(), document.isbn()), "—"));
        translatorsLabel.setText("Перекладачі: " + value(book.getTranslators(), "—"));
        formatLabel.setText("Формат: " + value(document.format(), detectFormat(book)));
        fileSizeLabel.setText("Розмір файла: " + value(book.getFileSizeFormatted(), "—"));
        textStatsLabel.setText(formatTextStats(document));
        localStatusLabel.setText("Статус: " + book.getLocalStatus());
        ratingLabel.setText("Оцінка: " + value(book.getRateStars(), "—"));
        progressLabel.setText("Прочитано: " + book.getProgressFormatted());
        libraryRateLabel.setText("Рейтинг каталогу: " + (book.getLibraryRate() > 0 ? book.getLibraryRate() : "—"));
        sourceUrlLabel.setText("Джерело: " + value(book.getSourceUrl(), "—"));

        String annotation = firstNonBlank(book.getAnnotation(), document.annotation());
        annotationLabel.setText(annotation);
        reviewArea.setText(value(book.getReview(), ""));

        renderKeywordLinks(book.getKeywords());
        renderGroupLinks(session.groups());
        renderReviewLinks(book);
        renderToc(document.tocPreview());
        renderImages(document.images());
        showWarning(document.warning());
    }

    private void updateBasicUI(BookDto book) {
        titleLabel.setText(value(book.getTitle(), "Без назви"));
        renderAuthorLinks(book.getAuthors(), book.getAuthorsText());
        renderSeriesLink(book.getSeries());
        renderGenreLinks(book.getGenreItems(), book.getGenresText());
        languageLabel.setText("Мова: " + value(book.getLanguage(), "—"));
        sourceLanguageLabel.setText("Мова оригіналу: —");
        yearLabel.setText("Рік: " + (book.getYear() != null && book.getYear() > 0 ? book.getYear() : "—"));
        publisherLink.setText("Видавництво: " + value(book.getPublisher(), "—"));
        isbnLabel.setText("ISBN: " + value(book.getIsbn(), "—"));
        translatorsLabel.setText("Перекладачі: " + value(book.getTranslators(), "—"));
        annotationLabel.setText(value(book.getAnnotation(), ""));
        reviewArea.setText(value(book.getReview(), ""));
        formatLabel.setText("Формат: " + detectFormat(book));
        fileSizeLabel.setText("Розмір файла: " + value(book.getFileSizeFormatted(), "—"));
        localStatusLabel.setText("Статус: " + book.getLocalStatus());
        ratingLabel.setText("Оцінка: " + value(book.getRateStars(), "—"));
        progressLabel.setText("Прочитано: " + book.getProgressFormatted());
        libraryRateLabel.setText("Рейтинг каталогу: " + (book.getLibraryRate() > 0 ? book.getLibraryRate() : "—"));
        sourceUrlLabel.setText("Джерело: " + value(book.getSourceUrl(), "—"));
        renderKeywordLinks(book.getKeywords());
        tocPreviewBox.getChildren().setAll(new Label("—"));
        renderImages(List.of());
    }

    private void showLoading(BookDto book) {
        clearUI();
        titleLabel.setText(value(book.getTitle(), "Без назви"));
        documentWarningLabel.setText("Завантаження відомостей…");
        documentWarningLabel.setVisible(true);
        documentWarningLabel.setManaged(true);
    }

    private void renderAuthorLinks(List<AuthorDto> authors, String fallback) {
        authorsLinksPane.getChildren().clear();
        List<AuthorDto> safeAuthors = authors != null ? authors : Collections.emptyList();
        if (!safeAuthors.isEmpty()) {
            for (AuthorDto author : safeAuthors) {
                if (author == null || author.getId() == null || author.getId().isBlank()) continue;
                Hyperlink link = new Hyperlink(value(author.getFullName(), author.getShortName()));
                link.setOnAction(event -> navigationService.navigateToAuthor(AuthorId.fromString(author.getId())));
                authorsLinksPane.getChildren().add(link);
            }
        }
        if (authorsLinksPane.getChildren().isEmpty()) authorsLinksPane.getChildren().add(new Label(value(fallback, "—")));
    }

    private void renderSeriesLink(String series) {
        seriesLinksPane.getChildren().clear();
        if (series == null || series.isBlank()) {
            seriesLinksPane.getChildren().add(new Label("—"));
            return;
        }
        Hyperlink link = new Hyperlink(series);
        link.setOnAction(event -> navigationService.navigateToSeriesByName(series));
        seriesLinksPane.getChildren().add(link);
    }

    private void renderGenreLinks(List<GenreDto> genres, String fallback) {
        genresLinksPane.getChildren().clear();
        List<GenreDto> safeGenres = genres != null ? genres : Collections.emptyList();
        List<String> siblingCodes = safeGenres.stream()
                .filter(java.util.Objects::nonNull)
                .map(GenreDto::getCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        if (!safeGenres.isEmpty()) {
            for (GenreDto genre : safeGenres) {
                if (genre == null || genre.getCode() == null || genre.getCode().isBlank()) continue;
                if (!localizationService.shouldDisplayGenre(genre.getCode(), siblingCodes)) continue;
                String label = localizationService.genreName(genre.getCode(), genre.getName());
                if (label == null || label.isBlank()) continue;
                Hyperlink link = new Hyperlink(label);
                link.setOnAction(event -> navigationService.navigateToGenre(GenreId.fromCode(genre.getCode())));
                genresLinksPane.getChildren().add(link);
            }
        }
        // Raw fallback text may contain internal genre codes or top-level groups; never expose them.
        if (genresLinksPane.getChildren().isEmpty()) genresLinksPane.getChildren().add(new Label("—"));
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
        List<GroupDto> safeGroups = groups != null ? groups : Collections.emptyList();
        if (!safeGroups.isEmpty()) {
            for (GroupDto group : safeGroups) {
                if (group == null || group.getId() == null) continue;
                Hyperlink link = new Hyperlink(groupDisplayName(group.getName()));
                link.setOnAction(event -> navigationService.navigateToGroup(GroupId.fromLong(group.getId())));
                groupLinksPane.getChildren().add(link);
            }
        }
        if (groupLinksPane.getChildren().isEmpty()) groupLinksPane.getChildren().add(new Label("—"));
    }

    private void renderReviewLinks(BookDto book) {
        reviewLinksPane.getChildren().clear();
        boolean rated = book.getRate() > 0;
        boolean reviewed = book.getReview() != null && !book.getReview().isBlank();
        if (rated) addReviewLink("Оцінені", ReviewNavigationFilter.RATED);
        if (reviewed) addReviewLink("З відгуками", ReviewNavigationFilter.REVIEWED);
        if (rated && reviewed) addReviewLink("Оцінені з відгуками", ReviewNavigationFilter.RATED_AND_REVIEWED);
        if (reviewLinksPane.getChildren().isEmpty()) reviewLinksPane.getChildren().add(new Label("—"));
    }

    private void addReviewLink(String title, ReviewNavigationFilter filter) {
        Hyperlink link = new Hyperlink(localizationService.tr(title));
        link.setOnAction(event -> navigationService.navigateToReviews(filter));
        reviewLinksPane.getChildren().add(link);
    }

    private void renderToc(List<TocPreviewEntry> toc) {
        tocPreviewBox.getChildren().clear();
        if (toc == null || toc.isEmpty()) {
            tocPreviewBox.getChildren().add(new Label("—"));
            return;
        }
        for (TocPreviewEntry entry : toc) {
            Label label = new Label(entry.title());
            label.setWrapText(true);
            label.setStyle("-fx-padding: 0 0 0 " + Math.min(36, entry.level() * 12) + ";");
            tocPreviewBox.getChildren().add(label);
        }
    }

    private void renderImages(List<DocumentImageInfo> images) {
        int count = images == null ? 0 : images.size();
        imagesCountLabel.setText("Зображення: " + count);
        showImagesButton.setDisable(count == 0 || currentSession == null);
        showImagesButton.setVisible(count > 0);
        showImagesButton.setManaged(count > 0);
    }

    private void showWarning(String warning) {
        boolean visible = warning != null && !warning.isBlank();
        documentWarningLabel.setText(visible ? warning : "");
        documentWarningLabel.setVisible(visible);
        documentWarningLabel.setManaged(visible);
    }

    @FXML
    private void onShowImages() {
        RichBookDetailsSession session = currentSession;
        if (session == null || session.inspection().images().isEmpty()) return;
        List<DocumentImageInfo> images = session.inspection().images();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Зображення — " + session.book().getTitle());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Pagination pagination = new Pagination(images.size(), 0);
        pagination.setPageFactory(index -> createImagePage(session, images.get(index)));
        dialog.getDialogPane().setContent(pagination);
        dialog.getDialogPane().setPrefSize(780, 620);
        dialog.show();
    }

    private Node createImagePage(RichBookDetailsSession session, DocumentImageInfo info) {
        StackPane pane = new StackPane();
        pane.setAlignment(Pos.CENTER);
        ProgressIndicator progress = new ProgressIndicator();
        pane.getChildren().add(progress);

        if (info.length() > MAX_GALLERY_IMAGE_BYTES) {
            pane.getChildren().setAll(new Label("Зображення занадто велике для preview"));
            return pane;
        }

        backgroundExecutor.submit(() -> readImage(session, info.id()))
                .thenAccept(bytes -> UiExecutor.runOnUiThread(() -> {
                    if (bytes == null || bytes.length == 0) {
                        pane.getChildren().setAll(new Label("Не вдалося прочитати зображення"));
                        return;
                    }
                    // Використовуємо правильний конструктор Image(InputStream, double, double, boolean, boolean)
                    Image image = new Image(new ByteArrayInputStream(bytes), 720, 530, true, true);
                    if (image.isError()) {
                        pane.getChildren().setAll(new Label("Непідтримуваний формат зображення"));
                    } else {
                        ImageView view = new ImageView(image);
                        view.setPreserveRatio(true);
                        view.setFitWidth(720);
                        view.setFitHeight(530);
                        pane.getChildren().setAll(view);
                    }
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> pane.getChildren().setAll(new Label("Помилка preview")));
                    return null;
                });
        return pane;
    }

    private static byte[] readImage(RichBookDetailsSession session, String id) throws Exception {
        try (InputStream in = session.openImage(id).orElse(null)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_GALLERY_IMAGE_BYTES) return null;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
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

    private static String formatTextStats(DocumentInspection document) {
        if (document == null || !document.parsed()) return "Текст: —";
        String chars = document.characterCount() > 0 ? String.format(Locale.ROOT, "%,d", document.characterCount()) : "—";
        String words = document.wordCount() > 0 ? String.format(Locale.ROOT, "%,d", document.wordCount()) : "—";
        return "Текст: " + chars + " символів · " + words + " слів · " + document.chapterCount() + " розділів";
    }

    private static String year(BookDto book, DocumentInspection document) {
        if (book.getYear() != null && book.getYear() > 0) return String.valueOf(book.getYear());
        String fileYear = document == null ? "" : document.year();
        return fileYear == null || fileYear.isBlank() ? "—" : fileYear;
    }

    private static String detectFormat(BookDto book) {
        String name = firstNonBlank(book.getArchiveEntry(), book.getFileName());
        if (name.isBlank()) return "—";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "—";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void clearUI() {
        titleLabel.setText("Назва");
        authorsLinksPane.getChildren().setAll(new Label("—"));
        seriesLinksPane.getChildren().setAll(new Label("—"));
        genresLinksPane.getChildren().setAll(new Label("—"));
        languageLabel.setText("Мова: —");
        sourceLanguageLabel.setText("Мова оригіналу: —");
        yearLabel.setText("Рік: —");
        publisherLink.setText("Видавництво: —");
        publisherLink.setDisable(true);
        isbnLabel.setText("ISBN: —");
        translatorsLabel.setText("Перекладачі: —");
        formatLabel.setText("Формат: —");
        fileSizeLabel.setText("Розмір файла: —");
        textStatsLabel.setText("Текст: —");
        localStatusLabel.setText("Статус: —");
        ratingLabel.setText("Оцінка: —");
        progressLabel.setText("Прочитано: —");
        libraryRateLabel.setText("Рейтинг каталогу: —");
        sourceUrlLabel.setText("Джерело: —");
        annotationLabel.setText("");
        reviewArea.clear();
        keywordsLinksPane.getChildren().setAll(new Label("—"));
        groupLinksPane.getChildren().setAll(new Label("—"));
        reviewLinksPane.getChildren().setAll(new Label("—"));
        tocPreviewBox.getChildren().setAll(new Label("—"));
        imagesCountLabel.setText("Зображення: 0");
        showImagesButton.setDisable(true);
        showImagesButton.setVisible(false);
        showImagesButton.setManaged(false);
        showWarning("");
    }

    private BookDto currentBook() {
        return currentSession != null ? currentSession.book() : appState.getBookDetails().getCurrentBook();
    }

    private void closeCurrentSession() {
        RichBookDetailsSession session = currentSession;
        currentSession = null;
        if (session != null) session.close();
    }

    @PreDestroy
    public void cleanup() {
        loadGeneration.incrementAndGet();
        BookDetailsViewModel vm = appState.getBookDetails();
        if (bookChangeListener != null) {
            vm.currentBookProperty().removeListener(bookChangeListener);
            bookChangeListener = null;
        }
        closeCurrentSession();
    }

    @FXML
    private void onRead() {
        BookDto book = currentBook();
        if (book != null) {
            navigationService.readBook(book);
        }
    }

    @FXML
    private void onEdit() {
        BookDto book = currentBook();
        if (book != null) log.info("Редагування книги: {}", book.getTitle());
    }

    @FXML
    private void onOpenFolder() {
        BookDto book = currentBook();
        if (book != null) navigationService.openBookFolder(book);
    }
}