package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.application.query.BookQuery;
import com.myhomelibcorp.application.query.Pagination;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.navigation.AuthorNode;
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import javafx.application.Platform;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NavigationManager {

    private final AuthorRepository authorRepository;
    private final BookQueryRepository bookQueryRepository;
    private final BackgroundExecutor executor;
    private final GenreService genreService;

    public void loadAuthors(TreeView<LibraryNode> authorsTree,
                            Consumer<AuthorId> onAuthorSelected,
                            Runnable onLoaded) {
        executor.submit(() -> authorRepository.findAll())
                .thenAccept(authors -> Platform.runLater(() -> {
                    TreeItem<LibraryNode> root = new TreeItem<>(null);
                    root.setExpanded(true);
                    authors.stream()
                            .sorted(Comparator.comparing(Author::getLastName))
                            .forEach(author ->
                                    root.getChildren().add(new TreeItem<>(new AuthorNode(author)))
                            );
                    authorsTree.setRoot(root);
                    authorsTree.setShowRoot(false);
                    authorsTree.setCellFactory(tv -> new TreeCell<>() {
                        @Override
                        protected void updateItem(LibraryNode item, boolean empty) {
                            super.updateItem(item, empty);
                            if (empty || item == null) setText(null);
                            else setText(item.toString());
                        }
                    });
                    authorsTree.getSelectionModel().selectedItemProperty().addListener(
                            (obs, oldVal, newVal) -> {
                                if (newVal != null && newVal.getValue() instanceof AuthorNode) {
                                    AuthorId id = ((AuthorNode) newVal.getValue()).author().getId();
                                    onAuthorSelected.accept(id);
                                }
                            }
                    );
                    if (onLoaded != null) onLoaded.run();
                }))
                .exceptionally(ex -> {
                    log.error("Помилка завантаження авторів", ex);
                    return null;
                });
    }

    public void loadBooksByAuthor(AuthorId authorId, Consumer<List<BookDto>> onResult) {
        BookQuery query = BookQuery.builder()
                .authorId(authorId)
                .pagination(Pagination.of(10000, 0))
                .build();

        executor.submit(() -> bookQueryRepository.find(query))
                .thenAccept(books -> {
                    books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                            .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
                    List<BookDto> dtos = books.stream().map(this::toDto).collect(Collectors.toList());
                    Platform.runLater(() -> onResult.accept(dtos));
                })
                .exceptionally(ex -> {
                    log.error("Помилка завантаження книг автора", ex);
                    return null;
                });
    }

    private BookDto toDto(Book book) {
        String genresText = book.getGenres().stream()
                .map(genre -> genreService.getGenreName(genre.getId().asString()))
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
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .build();
    }
}