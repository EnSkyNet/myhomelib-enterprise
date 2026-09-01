package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.author.AuthorWorkspaceController;
import com.myhomelibcorp.ui.book.BookWorkspaceController;
import com.myhomelibcorp.ui.group.GroupWorkspaceController;
import com.myhomelibcorp.ui.reader.NewReaderWorkspaceController;
import com.myhomelibcorp.ui.search.SearchWorkspaceController;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.Pane;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class FxmlLoaderFactory {

    private final ApplicationContext springContext;

    public Pane loadWorkspace(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            loader.setControllerFactory(springContext::getBean);
            Pane pane = loader.load();

            Object controller = loader.getController();
            if (controller != null) {
                pane.setUserData(controller);
            }

            return pane;
        } catch (IOException e) {
            log.error("Failed to load FXML: {}", fxmlPath, e);
            throw new RuntimeException("Не вдалося завантажити FXML: " + fxmlPath, e);
        }
    }

    public Pane loadAuthorWorkspace(AuthorId authorId) {
        return loadAuthorWorkspace(authorId, false);
    }

    public Pane loadAuthorWorkspace(AuthorId authorId, boolean downloadedOnly) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/author-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane pane = loader.load();

            AuthorWorkspaceController controller = loader.getController();
            if (authorId == null) {
                throw new IllegalArgumentException("AuthorId не може бути null");
            }
            controller.setDownloadedOnly(downloadedOnly);
            controller.setAuthorId(authorId);

            pane.setUserData(controller);
            return pane;
        } catch (IOException e) {
            log.error("Не вдалося завантажити AuthorWorkspace", e);
            throw new RuntimeException("Не вдалося завантажити /view/author-workspace.fxml", e);
        }
    }

    public Pane loadBookWorkspace(BookId bookId) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/book-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane pane = loader.load();
            BookWorkspaceController controller = loader.getController();
            controller.setBookId(bookId);
            pane.setUserData(controller);
            return pane;
        } catch (IOException e) {
            log.error("Failed to load book workspace", e);
            throw new RuntimeException(e);
        }
    }

    public Pane loadGroupWorkspace(Group group) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/groups-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane pane = loader.load();
            GroupWorkspaceController controller = loader.getController();
            if (group != null) {
                controller.setGroup(group);
            }
            pane.setUserData(controller);
            return pane;
        } catch (IOException e) {
            log.error("Failed to load group workspace", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * НОВИЙ МЕТОД: завантажує новий Reader Workspace (без WebView, на Canvas).
     */
    public Pane loadNewReaderWorkspace(BookId bookId) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/new-reader-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane pane = loader.load();

            NewReaderWorkspaceController controller = loader.getController();
            if (bookId != null) {
                controller.setBookId(bookId);
            }

            pane.setUserData(controller);
            return pane;
        } catch (IOException e) {
            log.error("Failed to load new reader workspace", e);
            throw new RuntimeException(e);
        }
    }

    public Pane loadSearchWorkspace(String query) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/search-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane pane = loader.load();
            SearchWorkspaceController controller = loader.getController();
            controller.setInitialQuery(query);
            pane.setUserData(controller);
            return pane;
        } catch (IOException e) {
            log.error("Failed to load search workspace", e);
            throw new RuntimeException(e);
        }
    }

    public Pane loadSearchWorkspace(List<BookDto> results) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/search-workspace.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Pane pane = loader.load();
            SearchWorkspaceController controller = loader.getController();
            controller.setResults(results);
            pane.setUserData(controller);
            return pane;
        } catch (IOException e) {
            log.error("Failed to load search workspace with results", e);
            throw new RuntimeException(e);
        }
    }
}