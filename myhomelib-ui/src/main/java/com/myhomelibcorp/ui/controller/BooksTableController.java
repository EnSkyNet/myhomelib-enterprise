package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
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

    private TableView<BookDto> tableView;
    private Label countLabel;

    public void setupBookTable(TableView<BookDto> tableView, Label countLabel) {
        this.tableView = tableView;
        this.countLabel = countLabel;
        log.info("✅ BooksTableController.setupBookTable: tableView={}, countLabel={}", tableView, countLabel);

        tableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (detailsController != null) {
                        detailsController.showBookDetails(newVal);
                    } else {
                        log.warn("detailsController is null");
                    }
                });
    }

    public void refresh() {
        if (tableView == null) {
            log.error("❌ tableView is null in refresh()!");
            return;
        }
        log.info("🔄 refresh() called");
        backgroundExecutor.submit(() -> {
            List<Book> books = bookQueryRepository.findAll(10000, 0);
            books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
            List<BookDto> dtos = books.stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                if (tableView == null) {
                    log.error("❌ tableView is null in Platform.runLater!");
                    return;
                }
                tableView.getItems().setAll(dtos);
                countLabel.setText(dtos.size() + " книг");
                if (!dtos.isEmpty()) {
                    tableView.getSelectionModel().selectFirst();
                } else {
                    detailsController.clearDetails();
                }
            });
            return null;
        });
    }

    public void loadBooksByAuthor(AuthorId authorId) {
        if (tableView == null) {
            log.error("❌ tableView is null in loadBooksByAuthor()!");
            return;
        }
        log.info("📚 loadBooksByAuthor: authorId={}", authorId != null ? authorId.asString() : "null");
        backgroundExecutor.submit(() -> {
            List<Book> books = bookQueryRepository.findByAuthorId(authorId, 10000, 0);
            log.info("📊 Отримано {} книг з репозиторію", books.size());
            books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                    .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
            List<BookDto> dtos = books.stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                if (tableView == null) {
                    log.error("❌ tableView is null in Platform.runLater (loadBooksByAuthor)!");
                    return;
                }
                tableView.getItems().setAll(dtos);
                countLabel.setText(dtos.size() + " книг");
                log.info("🔄 Оновлено таблицю: {} книг", dtos.size());
                if (!dtos.isEmpty()) {
                    tableView.getSelectionModel().selectFirst();
                } else {
                    detailsController.clearDetails();
                }
            });
            return null;
        });
    }

    public void filterBooksBySeries(String series) {
        if (tableView == null) {
            log.error("❌ tableView is null in filterBooksBySeries()!");
            return;
        }
        backgroundExecutor.submit(() -> {
            List<Book> allBooks = bookQueryRepository.findAll(10000, 0);
            List<BookDto> filtered = allBooks.stream()
                    .filter(b -> series.equals(b.getSeries()))
                    .sorted(Comparator.comparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)))
                    .map(this::toDto)
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                if (tableView == null) return;
                tableView.getItems().setAll(filtered);
                countLabel.setText(filtered.size() + " книг");
                if (!filtered.isEmpty()) {
                    tableView.getSelectionModel().selectFirst();
                } else {
                    detailsController.clearDetails();
                }
            });
            return null;
        });
    }

    public void filterBooksByGenre(String genre) {
        if (tableView == null) {
            log.error("❌ tableView is null in filterBooksByGenre()!");
            return;
        }
        backgroundExecutor.submit(() -> {
            List<Book> allBooks = bookQueryRepository.findAll(10000, 0);
            List<BookDto> filtered = allBooks.stream()
                    .filter(b -> b.genresText() != null && b.genresText().contains(genre))
                    .sorted(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo)))
                    .map(this::toDto)
                    .collect(Collectors.toList());

            Platform.runLater(() -> {
                if (tableView == null) return;
                tableView.getItems().setAll(filtered);
                countLabel.setText(filtered.size() + " книг");
                if (!filtered.isEmpty()) {
                    tableView.getSelectionModel().selectFirst();
                } else {
                    detailsController.clearDetails();
                }
            });
            return null;
        });
    }

    private BookDto toDto(Book book) {
        return BookDto.builder()
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(book.genresText())
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