package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class NavigationManager {

    private final AuthorRepository authorRepository;
    private final BookQueryRepository bookQueryRepository;
    private final BackgroundExecutor executor;

    /**
     * Асинхронно завантажує авторів і будує дерево з LibraryNode.
     * При виборі автора викликається onAuthorSelected з AuthorId.
     */
    public void loadAuthors(TreeView<LibraryNode> authorsTree, Consumer<AuthorId> onAuthorSelected) {
        executor.submit(() -> authorRepository.findAll())
                .thenAccept(authors -> Platform.runLater(() -> {
                    TreeItem<LibraryNode> root = new TreeItem<>(new AuthorNode(null)); // корінь
                    root.setExpanded(true);

                    authors.stream()
                            .sorted(Comparator.comparing(Author::getLastName))
                            .forEach(author ->
                                    root.getChildren().add(new TreeItem<>(new AuthorNode(author)))
                            );

                    authorsTree.setRoot(root);
                    authorsTree.setShowRoot(false);

                    // CellFactory для різних типів вузлів
                    authorsTree.setCellFactory(tv -> new TreeCell<>() {
                        @Override
                        protected void updateItem(LibraryNode item, boolean empty) {
                            super.updateItem(item, empty);
                            if (empty || item == null) {
                                setText(null);
                            } else {
                                setText(item.toString());
                            }
                        }
                    });

                    // Слухач вибору
                    authorsTree.getSelectionModel().selectedItemProperty().addListener(
                            (obs, oldVal, newVal) -> {
                                if (newVal != null && newVal.getValue() != null) {
                                    LibraryNode node = newVal.getValue();
                                    if (node instanceof AuthorNode authorNode) {
                                        Author author = authorNode.author();
                                        if (author != null) {
                                            onAuthorSelected.accept(author.getId());
                                        }
                                    }
                                }
                            }
                    );

                    log.info("Завантажено {} авторів", authors.size());
                }))
                .exceptionally(ex -> {
                    log.error("Помилка завантаження авторів", ex);
                    return null;
                });
    }

    /**
     * Асинхронно завантажує книги автора, сортує за серією та номером.
     */
    public void loadBooksByAuthor(AuthorId authorId, Consumer<List<BookDto>> onResult) {
        executor.submit(() -> bookQueryRepository.findByAuthorId(authorId, 10000, 0))
                .thenAccept(books -> {
                    books.sort(Comparator.comparing(Book::getSeries, Comparator.nullsLast(String::compareTo))
                            .thenComparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo)));
                    List<BookDto> dtos = books.stream().map(this::toDto).toList();
                    Platform.runLater(() -> onResult.accept(dtos));
                })
                .exceptionally(ex -> {
                    log.error("Помилка завантаження книг автора", ex);
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
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .build();
    }
}