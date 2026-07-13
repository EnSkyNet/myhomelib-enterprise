package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultNavigationService implements NavigationService {

    private final SessionService sessionService;
    private final MainController mainController;
    private final WorkspaceManager workspaceManager;

    @Override
    public void navigateToAuthor(AuthorId authorId) {
        sessionService.saveSelectedAuthorId(authorId.asString());
        mainController.showAuthorWorkspace(authorId);
        mainController.updateNavigationButtons();
    }

    @Override
    public void navigateToSeries(SeriesId seriesId) {
        mainController.showSeriesWorkspace(seriesId);
        mainController.updateNavigationButtons();
    }

    @Override
    public void navigateToSeriesByName(String seriesName) {
        log.info("Navigating to series by name: {}", seriesName);
        // TODO: знайти SeriesId за назвою
    }

    @Override
    public void navigateToGenre(GenreId genreId) {
        mainController.showGenreWorkspace(genreId);
        mainController.updateNavigationButtons();
    }

    @Override
    public void navigateToBook(BookId bookId) {
        mainController.showBookWorkspace(bookId);
        mainController.updateNavigationButtons();
    }

    @Override
    public void navigateToCollection(GroupId groupId) {
        mainController.showCollectionWorkspace();
        mainController.updateNavigationButtons();
    }

    @Override
    public void showSearchResults(List<BookDto> results) {
        mainController.showSearchResults(results);
        mainController.updateNavigationButtons();
    }

    @Override
    public void clearSearch() {
        // Скидаємо стан пошуку
    }

    @Override
    public void openBookFile(BookDto book) {
        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();
        Path filePath;
        if (root != null && !root.isBlank()) {
            filePath = Paths.get(root, folder != null ? folder : "", fileName);
        } else if (folder != null && !folder.isBlank()) {
            filePath = Paths.get(folder, fileName);
        } else {
            filePath = Paths.get(fileName);
        }
        File file = filePath.toFile();
        if (file.exists()) {
            try {
                Desktop.getDesktop().open(file);
            } catch (IOException e) {
                log.error("Failed to open file: {}", filePath, e);
            }
        } else {
            log.warn("File not found: {}", filePath);
        }
    }

    @Override
    public void openBookFolder(BookDto book) {
        String folder = book.getFolder();
        if (folder == null || folder.isBlank()) return;
        File dir = new File(folder);
        if (dir.exists() && dir.isDirectory()) {
            try {
                Desktop.getDesktop().open(dir);
            } catch (IOException e) {
                log.error("Failed to open folder: {}", folder, e);
            }
        }
    }

    @Override
    public void readBook(BookDto book) {
        if (book != null) {
            mainController.showReaderWorkspace(BookId.fromString(book.getId()));
            mainController.updateNavigationButtons();
        }
    }

    @Override
    public boolean canGoBack() {
        return workspaceManager.canGoBack();
    }

    @Override
    public boolean canGoForward() {
        return workspaceManager.canGoForward();
    }

    @Override
    public void goBack() {
        workspaceManager.goBack();
        mainController.updateNavigationButtons();
    }

    @Override
    public void goForward() {
        workspaceManager.goForward();
        mainController.updateNavigationButtons();
    }
}