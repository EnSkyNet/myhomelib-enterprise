package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;  // <-- ВИПРАВЛЕНО
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultNavigationService implements NavigationService {

    private final ApplicationState appState;
    private final SessionService sessionService;
    private final BookViewModelMapper viewModelMapper;

    @Override
    public void navigateToAuthor(AuthorId authorId) {
        sessionService.saveSelectedAuthorId(authorId.asString());
        log.info("Navigating to author: {}", authorId.asString());
    }

    @Override
    public void navigateToSeries(String seriesName) {
        log.info("Navigating to series: {}", seriesName);
    }

    @Override
    public void navigateToGenre(String genreCode) {
        log.info("Navigating to genre: {}", genreCode);
    }

    @Override
    public void navigateToBook(String bookId) {
        log.info("Navigating to book: {}", bookId);
    }

    @Override
    public void showSearchResults(List<BookDto> books) {
        List<BookViewModel> vms = books.stream()
                .map(viewModelMapper::toViewModel)
                .collect(Collectors.toList());
        appState.getSearch().setResults(vms);
        appState.getBookTable().setBooks(vms);
    }

    @Override
    public void clearSearch() {
        appState.getSearch().clearResults();
        appState.getSearch().setQuery("");
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
    public void readBook(BookDto book) {
        log.info("Read book: {}", book.getTitle());
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
}