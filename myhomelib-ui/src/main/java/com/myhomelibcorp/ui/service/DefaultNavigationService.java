package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.series.Series;
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
    private final BookLoaderService bookLoaderService;
    private final SeriesRepository seriesRepository;

    @Override
    public void navigateToAuthor(AuthorId authorId) {
        if (authorId != null) {
            sessionService.saveSelectedAuthorId(authorId.asString());
        }
        mainController.showAuthorWorkspace(authorId);
        mainController.updateNavigationButtons();
    }

    @Override
    public void navigateToSeries(SeriesId seriesId) {
        log.info("Завантаження книг для серії: {}", seriesId);
        bookLoaderService.loadBooksBySeries(seriesId);
        mainController.updateNavigationButtons();
    }

    @Override
    public void navigateToSeriesByName(String seriesName) {
        log.info("Завантаження книг для серії за назвою: {}", seriesName);
        SeriesId seriesId = findSeriesIdByName(seriesName);
        if (seriesId != null) {
            bookLoaderService.loadBooksBySeries(seriesId);
        } else {
            bookLoaderService.loadBooksBySeriesByName(seriesName);
        }
        mainController.updateNavigationButtons();
    }

    private SeriesId findSeriesIdByName(String seriesName) {
        if (seriesName == null || seriesName.isBlank()) return null;
        try {
            List<Series> allSeries = seriesRepository.findAll();
            String normalized = seriesName.trim();
            for (Series s : allSeries) {
                if (s.getName() != null && s.getName().equalsIgnoreCase(normalized)) {
                    return s.getId();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to find series by name: {}", seriesName, e);
        }
        return null;
    }

    @Override
    public void navigateToGenre(GenreId genreId) {
        log.info("Завантаження книг для жанру: {}", genreId);
        bookLoaderService.loadBooksByGenre(genreId);
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
        String archiveEntry = book.getArchiveEntry();

        if (archiveEntry != null && !archiveEntry.isBlank()) {
            String archivePathStr = (folder != null && !folder.isBlank()) ? folder : fileName;
            if (archivePathStr == null || archivePathStr.isBlank()) {
                log.warn("Archive path is empty");
                return;
            }
            Path archivePath = buildFilePath(root, null, archivePathStr);
            File archiveFile = archivePath.toFile();
            if (archiveFile.exists()) {
                try {
                    Desktop.getDesktop().open(archiveFile);
                } catch (IOException e) {
                    log.error("Failed to open archive: {}", archivePath, e);
                }
            } else {
                log.warn("Archive not found: {}", archivePath);
            }
            return;
        }

        Path filePath = buildFilePath(root, folder, fileName);
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

    private Path buildFilePath(String root, String folder, String fileName) {
        if (fileName != null && !fileName.isBlank()) {
            Path fileNamePath = Paths.get(fileName);
            if (fileNamePath.isAbsolute()) {
                return fileNamePath;
            }
        }

        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (folderPath.isAbsolute()) {
                if (fileName != null && !fileName.isBlank()) {
                    return folderPath.resolve(fileName);
                }
                return folderPath;
            }
        }

        if (root != null && !root.isBlank() && folder != null && !folder.isBlank()) {
            Path rootPath = Paths.get(root);
            Path folderPath = Paths.get(folder);
            if (fileName != null && !fileName.isBlank()) {
                return rootPath.resolve(folderPath).resolve(fileName);
            }
            return rootPath.resolve(folderPath);
        }

        if (root != null && !root.isBlank() && fileName != null && !fileName.isBlank()) {
            return Paths.get(root).resolve(fileName);
        }

        if (fileName != null && !fileName.isBlank()) {
            return Paths.get(fileName);
        }
        if (folder != null && !folder.isBlank()) {
            return Paths.get(folder);
        }
        return Paths.get(".");
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