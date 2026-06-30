package com.myhomelibcorp.ui.components;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.Consumer;

public class BookInfoPanel extends VBox {

    private final ObjectProperty<BookDto> bookProperty = new SimpleObjectProperty<>();

    private final ImageView coverView = new ImageView();
    private final Label titleLabel = new Label();
    private final Hyperlink authorsLink = new Hyperlink();
    private final Hyperlink seriesLink = new Hyperlink();
    private final Hyperlink genresLink = new Hyperlink();
    private final Label annotationLabel = new Label(); // ТІЛЬКИ LABEL
    private final VBox metaBox = new VBox(4);

    private Consumer<String> onAuthorClicked;
    private Consumer<String> onSeriesClicked;
    private Consumer<String> onGenreClicked;
    private Consumer<BookDto> onAnnotationClicked;

    public BookInfoPanel() {
        configureUI();
        setupBindings();
        setupEventHandlers();
    }

    private void configureUI() {
        setSpacing(12);
        setPadding(new Insets(15));
        setStyle("-fx-background-color: #ffffff; -fx-border-color: #e0e0e0; -fx-border-width: 1;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);

        coverView.setFitWidth(180);
        coverView.setFitHeight(250);
        coverView.setPreserveRatio(true);
        coverView.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 12, 0, 0, 4);");

        HBox coverBox = new HBox(coverView);
        coverBox.setAlignment(Pos.CENTER);

        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 17));
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        VBox linksBox = new VBox(10);
        linksBox.getChildren().addAll(
                createLinkRow("Автори:", authorsLink),
                createLinkRow("Серія:", seriesLink),
                createLinkRow("Жанри:", genresLink)
        );

        Label annLabel = new Label("Анотація:");
        annLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        // --- НАЛАШТУВАННЯ LABEL ДЛЯ АНОТАЦІЇ ---
        annotationLabel.setWrapText(true);
        annotationLabel.setStyle("-fx-font-size: 13.5px; -fx-padding: 4 0 4 0;");
        annotationLabel.setMaxWidth(Double.MAX_VALUE);
        annotationLabel.setMaxHeight(Double.MAX_VALUE);
        annotationLabel.setPrefHeight(Region.USE_COMPUTED_SIZE);
        annotationLabel.setMinHeight(100); // щоб завжди було видно хоч щось

        Label metaLabel = new Label("Метадані:");
        metaLabel.setFont(Font.font("System", FontWeight.BOLD, 13));

        metaBox.setStyle("-fx-padding: 0;");
        metaBox.setFillWidth(true);

        // Анотація займає весь вільний простір
        VBox.setVgrow(annotationLabel, Priority.ALWAYS);

        getChildren().addAll(
                coverBox,
                titleLabel,
                linksBox,
                annLabel,
                annotationLabel,
                metaLabel,
                metaBox
        );
    }

    private HBox createLinkRow(String labelText, Hyperlink link) {
        Label label = new Label(labelText);
        label.setPrefWidth(70);
        label.setStyle("-fx-font-weight: bold;");
        label.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, label, link);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private void setupBindings() {
        bookProperty.addListener((obs, oldBook, newBook) -> {
            if (newBook == null) {
                clear();
            } else {
                updateUI(newBook);
            }
        });
    }

    private void updateUI(BookDto book) {
        titleLabel.setText(book.getTitle() != null ? book.getTitle() : "Без назви");
        authorsLink.setText(book.getAuthorsText() != null ? book.getAuthorsText() : "—");
        seriesLink.setText(book.getSeries() != null && !book.getSeries().isBlank() ? book.getSeries() : "—");
        genresLink.setText(book.getGenresText() != null ? book.getGenresText() : "—");
        annotationLabel.setText(book.getAnnotation() != null ? book.getAnnotation() : "");

        metaBox.getChildren().clear();
        metaBox.getChildren().addAll(
                createMetaLabel("Файл: " + (book.getFileName() != null ? book.getFileName() : "—")),
                createMetaLabel("Папка: " + (book.getFolder() != null ? book.getFolder() : "—")),
                createMetaLabel("Розмір: " + book.getFileSizeFormatted()),
                createMetaLabel("Мова: " + (book.getLanguage() != null ? book.getLanguage() : "—")),
                createMetaLabel("Рейтинг: " + book.getRateStars()),
                createMetaLabel("Прогрес: " + book.getProgressFormatted()),
                createMetaLabel("Додано: " + book.getCreatedAtFormatted()),
                createMetaLabel("Оновлено: " + book.getUpdateDateFormatted())
        );
    }

    private Label createMetaLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 13px; -fx-padding: 1 0 1 0;");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private void setupEventHandlers() {
        authorsLink.setOnAction(e -> {
            if (onAuthorClicked != null && bookProperty.get() != null) {
                onAuthorClicked.accept(bookProperty.get().getAuthorsText());
            }
        });

        seriesLink.setOnAction(e -> {
            if (onSeriesClicked != null && bookProperty.get() != null) {
                onSeriesClicked.accept(bookProperty.get().getSeries());
            }
        });

        genresLink.setOnAction(e -> {
            if (onGenreClicked != null && bookProperty.get() != null) {
                onGenreClicked.accept(bookProperty.get().getGenresText());
            }
        });

        annotationLabel.setOnMouseClicked(e -> {
            if (onAnnotationClicked != null && bookProperty.get() != null) {
                onAnnotationClicked.accept(bookProperty.get());
            }
        });
    }

    public ObjectProperty<BookDto> bookProperty() {
        return bookProperty;
    }

    public void setCover(Image image) {
        coverView.setImage(image);
    }

    public void setOnAuthorClicked(Consumer<String> handler) {
        this.onAuthorClicked = handler;
    }

    public void setOnSeriesClicked(Consumer<String> handler) {
        this.onSeriesClicked = handler;
    }

    public void setOnGenreClicked(Consumer<String> handler) {
        this.onGenreClicked = handler;
    }

    public void setOnAnnotationClicked(Consumer<BookDto> handler) {
        this.onAnnotationClicked = handler;
    }

    public void clear() {
        bookProperty.set(null);
        titleLabel.setText("");
        authorsLink.setText("");
        seriesLink.setText("");
        genresLink.setText("");
        annotationLabel.setText("");
        metaBox.getChildren().clear();
        coverView.setImage(null);
    }
    public ImageView getCoverImageView() {
        return coverView;
    }
}