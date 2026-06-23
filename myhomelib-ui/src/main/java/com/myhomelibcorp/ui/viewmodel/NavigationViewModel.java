package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.navigation.AuthorNode;
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.TreeItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationViewModel {

    private final AuthorRepository authorRepository;
    private final BackgroundExecutor backgroundExecutor;

    private final ObjectProperty<TreeItem<LibraryNode>> authorsRoot = new SimpleObjectProperty<>();
    private final ObjectProperty<AuthorId> selectedAuthorId = new SimpleObjectProperty<>();

    public ObjectProperty<TreeItem<LibraryNode>> authorsRootProperty() {
        return authorsRoot;
    }

    public ObjectProperty<AuthorId> selectedAuthorIdProperty() {
        return selectedAuthorId;
    }

    public void loadAuthors() {
        backgroundExecutor.submit(() -> authorRepository.findAll())
                .thenAccept(authors -> Platform.runLater(() -> {
                    TreeItem<LibraryNode> root = new TreeItem<>(null);
                    root.setExpanded(true);

                    authors.stream()
                            .sorted(Comparator.comparing(Author::getLastName))
                            .forEach(author -> root.getChildren().add(new TreeItem<>(new AuthorNode(author))));

                    authorsRoot.set(root);
                    log.info("Завантажено {} авторів", authors.size());
                }))
                .exceptionally(ex -> {
                    log.error("Помилка завантаження авторів", ex);
                    return null;
                });
    }

    public void selectAuthor(AuthorId authorId) {
        selectedAuthorId.set(authorId);
    }
}