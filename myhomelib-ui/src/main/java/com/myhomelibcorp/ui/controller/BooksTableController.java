package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BooksTableController {

    private final BookQueryRepository bookQueryRepository;
    private final BackgroundExecutor backgroundExecutor;
    private final DetailsController detailsController;
    private final GenreService genreService; // ← порт

    private TableView<BookDto> tableView;
    private Label countLabel;

    public void setupBookTable(TableView<BookDto> tableView, Label countLabel) {
        this.tableView = tableView;
        this.countLabel = countLabel;
        tableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> detailsController.showBookDetails(newVal));
    }

    public void refresh() {
        backgroundExecutor.submit(() -> {
            List<Book> books = bookQueryRepository.findAll(10000, 0);
            books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
            List<BookDto> dtos = books.stream().map(this::toDto).collect(Collectors.toList());
            Platform.runLater(() -> {
                tableView.getItems().setAll(dtos);
                countLabel.setText(dtos.size() + " книг");
                if (!dtos.isEmpty()) tableView.getSelectionModel().selectFirst();
                else detailsController.clearDetails();
            });
            return null;
        });
    }

    public void loadBooksByAuthor(AuthorId authorId) {
        backgroundExecutor.submit(() -> {
            List<Book> books = bookQueryRepository.findByAuthorId(authorId, 10000, 0);
            books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
            List<BookDto> dtos = books.stream().map(this::toDto).collect(Collectors.toList());
            Platform.runLater(() -> {
                tableView.getItems().setAll(dtos);
                countLabel.setText(dtos.size() + " книг");
                if (!dtos.isEmpty()) tableView.getSelectionModel().selectFirst();
                else detailsController.clearDetails();
            });
            return null;
        });
    }

    public void filterBooksBySeries(String series) {
        backgroundExecutor.submit(() -> {
            List<Book> allBooks = bookQueryRepository.findAll(10000, 0);
            List<BookDto> filtered = allBooks.stream()
                    .filter(b -> series.equals(b.getSeries()))
                    .sorted(Comparator.comparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)))
                    .map(this::toDto)
                    .collect(Collectors.toList());
            Platform.runLater(() -> {
                tableView.getItems().setAll(filtered);
                countLabel.setText(filtered.size() + " книг");
                if (!filtered.isEmpty()) tableView.getSelectionModel().selectFirst();
                else detailsController.clearDetails();
            });
            return null;
        });
    }

    public void filterBooksByGenre(String genreName) {
        backgroundExecutor.submit(() -> {
            List<Book> allBooks = bookQueryRepository.findAll(10000, 0);
            List<BookDto> filtered = allBooks.stream()
                    .filter(b -> b.getGenres().stream()
                            .anyMatch(g -> genreService.getGenreName(g.getId().asString()).equals(genreName)))
                    .sorted(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo)))
                    .map(this::toDto)
                    .collect(Collectors.toList());
            Platform.runLater(() -> {
                tableView.getItems().setAll(filtered);
                countLabel.setText(filtered.size() + " книг");
                if (!filtered.isEmpty()) tableView.getSelectionModel().selectFirst();
                else detailsController.clearDetails();
            });
            return null;
        });
    }

    private BookDto toDto(Book book) {
        String genresText = book.getGenres().stream()
                .map(g -> {
                    String code = g.getId().asString();
                    String name = genreService.getGenreName(code);
                    // Діагностика для перших кількох книг
                    if (!code.equals(name)) {
                        log.debug("Жанр: код='{}', назва='{}'", code, name);
                    }
                    return name;
                })
                .collect(Collectors.joining(", "));

        return BookDto.builder()
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(genresText)
                .sequenceNumber(book.getSequenceNumber())
                .rate(book.getRate())
                .progress(book.getProgress())
                .language(book.getLanguage() != null ? book.getLanguage().toString() : "")
                .fileSize(book.getFileSize())
                .fileName(book.getFileName())
                .folder(book.getFolder())
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .build();
    }
}