package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
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
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
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
    private final BookQueryRepository bookQueryRepository;
    private final BookMapper bookMapper;
    private final DictionaryCachePort dictionaryCache;
    private final NavigationPanelController navigationPanelController;
    private final BookResourcePort bookResourcePort;

    @PostConstruct
    public void init() {
        navigationPanelController.setNavigationCallbacks(
                this::navigateToAuthor,
                this::navigateToSeries,
                this::navigateToGenre
        );
        log.info("Navigation callbacks встановлено");
    }

    @Override
    public void navigateToAuthor(AuthorId authorId) {
        if (authorId != null) {
            sessionService.saveSelectedAuthorId(authorId.asString());
        }
        workspaceManager.showAuthorWorkspace(authorId);
        mainController.updateNavigationButtons();
    }

    @Override
    public void navigateToSeries(SeriesId seriesId) {
        log.info("Навігація до серії: {}", seriesId);
        Optional<Series> seriesOpt = dictionaryCache.getSeries(seriesId);
        if (seriesOpt.isEmpty()) {
            log.warn("Серію з ID {} не знайдено в кеші", seriesId);
            mainController.showSearchResults(List.of());
            mainController.updateNavigationButtons();
            return;
        }
        String seriesName = seriesOpt.get().getName();
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
        if (book == null) {
            log.warn("Спроба відкрити null книгу");
            return;
        }

        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();
        String archiveEntry = book.getArchiveEntry();

        log.debug("openBookFile: fileName='{}', folder='{}', root='{}', archiveEntry='{}'",
                fileName, folder, root, archiveEntry);

        // Якщо є archiveEntry - відкриваємо архів
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            String archivePathStr = (folder != null && !folder.isBlank()) ? folder : fileName;
            if (archivePathStr == null || archivePathStr.isBlank()) {
                log.warn("Archive path is empty");
                return;
            }
            Path archivePath = bookResourcePort.buildFilePath(root, null, archivePathStr);
            File archiveFile = archivePath.toFile();
            if (archiveFile.exists()) {
                try {
                    Desktop.getDesktop().open(archiveFile);
                    log.info("Відкрито архів: {}", archivePath);
                } catch (IOException e) {
                    log.error("Не вдалося відкрити архів: {}", archivePath, e);
                }
            } else {
                log.warn("Архів не знайдено: {}", archivePath);
            }
            return;
        }

        // Звичайний файл
        Path filePath = bookResourcePort.buildFilePath(root, folder, fileName);
        File file = filePath.toFile();
        if (file.exists()) {
            try {
                Desktop.getDesktop().open(file);
                log.info("Відкрито файл: {}", filePath);
            } catch (IOException e) {
                log.error("Не вдалося відкрити файл: {}", filePath, e);
            }
        } else {
            log.warn("Файл не знайдено: {}", filePath);
        }
    }

    @Override
    public void openBookFolder(BookDto book) {
        if (book == null) {
            log.warn("Спроба відкрити папку для null книги");
            return;
        }

        String folder = book.getFolder();
        if (folder == null || folder.isBlank()) {
            log.warn("Папка для книги {} не вказана", book.getTitle());
            return;
        }

        File dir = new File(folder);
        if (dir.exists() && dir.isDirectory()) {
            try {
                Desktop.getDesktop().open(dir);
                log.info("Відкрито папку: {}", folder);
            } catch (IOException e) {
                log.error("Не вдалося відкрити папку: {}", folder, e);
            }
        } else {
            log.warn("Папка не існує або не є директорією: {}", folder);
        }
    }

    @Override
    public void readBook(BookDto book) {
        if (book != null) {
            sessionService.saveLastOpenedBookId(book.getId());
            mainController.showReaderWorkspace(BookId.fromString(book.getId()));
            mainController.updateNavigationButtons();
            log.info("Відкрито книгу для читання: {}", book.getTitle());
        } else {
            log.warn("Спроба відкрити для читання null книгу");
        }
    }

    @Override
    public void navigateToPublisher(String publisherName) {
        log.info("Навігація до видавництва: {}", publisherName);
        if (publisherName == null || publisherName.isBlank()) {
            mainController.showSearchResults(List.of());
            return;
        }
        BookQuery query = BookQuery.builder()
                .text(publisherName)
                .pagination(Pagination.of(1000, 0))
                .build();
        List<Book> books = bookQueryRepository.find(query);
        List<BookDto> dtos = books.stream()
                .map(bookMapper::toDto)
                .collect(Collectors.toList());
        List<BookDto> filtered = dtos.stream()
                .filter(b -> publisherName.equalsIgnoreCase(b.getPublisher()))
                .collect(Collectors.toList());
        mainController.showSearchResults(filtered);
        mainController.updateNavigationButtons();
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

    // ==================== Допоміжні методи ====================

    private String normalizeSeriesName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}