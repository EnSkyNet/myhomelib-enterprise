package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.book.Book;
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
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultNavigationService implements NavigationService {

    private final SessionService sessionService;
    private final MainController mainController;
    private final WorkspaceManager workspaceManager;
    private final BookLoaderService bookLoaderService;
    private final SeriesRepository seriesRepository;
    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;

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
        log.info("Навігація до серії: {}", seriesId);
        Optional<Series> seriesOpt = seriesRepository.findById(seriesId);
        if (seriesOpt.isEmpty()) {
            log.warn("Серію з ID {} не знайдено", seriesId);
            mainController.showSearchResults(List.of());
            mainController.updateNavigationButtons();
            return;
        }
        String seriesName = seriesOpt.get().getName();
        log.info("Назва серії з таблиці series: '{}'", seriesName);
        navigateToSeriesByName(seriesName);
    }

    @Override
    public void navigateToSeriesByName(String seriesName) {
        log.info("Навігація до серії за назвою: {}", seriesName);
        if (seriesName == null || seriesName.isBlank()) {
            mainController.showSearchResults(List.of());
            return;
        }
        String normalized = normalizeSeriesName(seriesName);
        BookQuery allQuery = BookQuery.builder()
                .pagination(Pagination.of(10000, 0))
                .build();
        List<Book> allBooks = bookQueryRepository.find(allQuery);
        List<Book> filtered = allBooks.stream()
                .filter(b -> {
                    String bs = b.getSeries();
                    if (bs == null || bs.isBlank()) return false;
                    String normBs = normalizeSeriesName(bs);
                    return normBs.equals(normalized) || normBs.contains(normalized) || normalized.contains(normBs);
                })
                .collect(Collectors.toList());
        List<BookDto> dtos = filtered.stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
        mainController.showSearchResults(dtos);
        mainController.updateNavigationButtons();
    }

    @Override
    public void navigateToGenre(GenreId genreId) {
        log.info("Навігація до жанру: {}", genreId);
        BookQuery query = BookQuery.builder()
                .genreId(genreId)
                .pagination(Pagination.of(1000, 0))
                .build();
        List<Book> books = bookQueryRepository.find(query);
        List<BookDto> dtos = books.stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
        mainController.showSearchResults(dtos);
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
        // Not needed
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

    private String normalizeSeriesName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}