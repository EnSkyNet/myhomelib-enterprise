package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.navigation.ArchiveNavigationKey;
import com.myhomelibcorp.application.navigation.NavigationNodeDto;
import com.myhomelibcorp.application.navigation.NavigationMode;
import com.myhomelibcorp.application.navigation.ReviewNavigationFilter;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultNavigationService implements NavigationService {

    private final WorkspaceManager workspaceManager;
    private final SeriesRepository seriesRepository;
    private final NavigationPanelController navigationPanelController;
    private final BookResourcePort bookResourcePort;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final SessionService sessionService;

    @PostConstruct
    public void init() {
        navigationPanelController.setOnNodeSelected(this::navigateToNode);
        log.info("Navigation callbacks встановлено");
    }

    private void navigateToNode(NavigationNodeDto node) {
        if (node == null) return;
        switch (node.mode()) {
            case AUTHORS -> navigateToAuthor(AuthorId.fromString(node.id()));
            case SERIES -> navigateToSeries(SeriesId.fromString(node.id()));
            case GENRES -> navigateToGenre(GenreId.fromCode(node.id()));
            case YEARS -> navigateToYear(parseYear(node.id()));
            case LANGUAGES -> navigateToLanguage(node.id());
            case ARCHIVES -> navigateToArchive(ArchiveNavigationKey.decode(node.id()));
            case KEYWORDS -> navigateToKeyword(node.label());
            case GROUPS -> navigateToGroup(GroupId.fromLong(Long.parseLong(node.id())));
            case REVIEWS -> navigateToReviews(ReviewNavigationFilter.fromId(node.id()));
            case UPDATES -> navigateToUpdates();
            case ALREADY_READ -> navigateToAlreadyRead();
            case HISTORY -> navigateToHistory();
            case ALL_BOOKS -> navigateToAllBooks();
        }
    }

    @Override
    public void navigateToAuthor(AuthorId authorId) {
        workspaceManager.showAuthorWorkspace(authorId);
    }

    @Override
    public void navigateToSeries(SeriesId seriesId) {
        if (seriesId == null) return;
        log.info("Навігація до серії: {}", seriesId);
        workspaceManager.showSeriesWorkspace(seriesId);
    }

    @Override
    public void navigateToSeriesByName(String seriesName) {
        log.info("Навігація до серії за назвою: {}", seriesName);
        if (seriesName == null || seriesName.isBlank()) return;
        seriesRepository.findByName(seriesName)
                .ifPresentOrElse(
                        series -> workspaceManager.showSeriesWorkspace(series.getId()),
                        () -> {
                            log.warn("Серію не знайдено: {}", seriesName);
                            workspaceManager.showSearchResults(List.of());
                        });
    }

    @Override
    public void navigateToGenre(GenreId genreId) {
        if (genreId == null) return;
        log.info("Навігація до жанру: {}", genreId);
        workspaceManager.showGenreWorkspace(genreId);
    }


    @Override
    public void navigateToYear(int year) {
        if (year <= 0) return;
        log.info("Навігація до року: {}", year);
        workspaceManager.showYearWorkspace(year);
    }

    @Override
    public void navigateToLanguage(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) return;
        log.info("Навігація до мови: {}", languageCode);
        workspaceManager.showLanguageWorkspace(languageCode);
    }

    @Override
    public void navigateToArchive(ArchiveNavigationKey archive) {
        if (archive == null) return;
        log.info("Навігація до архіву: {}", archive.archivePath());
        workspaceManager.showArchiveWorkspace(archive);
    }

    @Override
    public void navigateToKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return;
        log.info("Навігація до ключового слова: {}", keyword);
        navigationPanelController.revealNode(NavigationMode.KEYWORDS, keyword.toLowerCase(java.util.Locale.ROOT));
        workspaceManager.showKeywordWorkspace(keyword);
    }

    @Override
    public void navigateToGroup(GroupId groupId) {
        if (groupId == null || groupId.asLong() == null) return;
        log.info("Навігація до групи: {}", groupId.asLong());
        navigationPanelController.revealNode(NavigationMode.GROUPS, groupId.toString());
        workspaceManager.showGroupBooksWorkspace(groupId);
    }

    @Override
    public void navigateToReviews(ReviewNavigationFilter filter) {
        if (filter == null) return;
        log.info("Навігація до review subset: {}", filter.id());
        navigationPanelController.revealNode(NavigationMode.REVIEWS, filter.id());
        workspaceManager.showReviewsWorkspace(filter);
    }

    @Override
    public void navigateToUpdates() {
        log.info("Навігація до оновлень каталогу");
        navigationPanelController.revealNode(NavigationMode.UPDATES, "updates");
        workspaceManager.showUpdatesWorkspace();
    }

    @Override
    public void navigateToAlreadyRead() {
        log.info("Навігація до прочитаних книг");
        workspaceManager.showAlreadyReadWorkspace();
    }

    @Override
    public void navigateToHistory() {
        log.info("Навігація до історії читання");
        workspaceManager.showHistoryWorkspace();
    }

    @Override
    public void navigateToAllBooks() {
        log.info("Навігація до всіх книг");
        workspaceManager.showAllBooksWorkspace();
    }

    @Override
    public void navigateToBook(BookId bookId) {
        workspaceManager.showBookWorkspace(bookId);
    }

    @Override
    public void showSearchResults(List<BookDto> results) {
        workspaceManager.showSearchResults(results);
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
        if (book == null) {
            log.warn("Спроба відкрити для читання null книгу");
            return;
        }
        bookDownloadCoordinator.ensureLocalForOpen(book).whenComplete((path, error) -> {
            if (error != null) return;
            javafx.application.Platform.runLater(() -> {
                sessionService.saveLastOpenedBookId(book.getId());
                workspaceManager.showNewReaderWorkspace(BookId.fromString(book.getId()));
                        log.info("Відкрито книгу для читання: {}", book.getTitle());
            });
        });
    }

    @Override
    public void navigateToPublisher(String publisherName) {
        log.info("Навігація до видавництва: {}", publisherName);
        if (publisherName == null || publisherName.isBlank()) return;
        workspaceManager.showPublisherWorkspace(publisherName);
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
    }

    @Override
    public void goForward() {
        workspaceManager.goForward();
    }

    // ==================== Допоміжні методи ====================

    private int parseYear(String value) {
        try {
            int year = Integer.parseInt(value);
            return year > 0 ? year : -1;
        } catch (NumberFormatException e) {
            log.warn("Некоректний рік у навігації: {}", value);
            return -1;
        }
    }

    private String normalizeSeriesName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}