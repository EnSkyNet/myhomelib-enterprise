package com.myhomelibcorp.application.session;

import com.myhomelibcorp.application.port.out.repository.SessionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.prefs.Preferences;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final SessionRepository sessionRepository;
    private final Preferences prefs = Preferences.userNodeForPackage(SessionService.class);

    @PostConstruct
    public void init() {
        // При старті завантажуємо останню книгу з Preferences у репозиторій
        String lastBookId = prefs.get("lastOpenedBookId", null);
        if (lastBookId != null && !lastBookId.isEmpty()) {
            sessionRepository.saveLastOpenedBookId(lastBookId);
            log.debug("Завантажено останню відкриту книгу: {}", lastBookId);
        }
    }

    public void saveLastOpenedBookId(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            return;
        }
        sessionRepository.saveLastOpenedBookId(bookId);
        prefs.put("lastOpenedBookId", bookId);
        log.debug("Збережено останню відкриту книгу: {}", bookId);
    }

    public String getLastOpenedBookId() {
        String id = sessionRepository.getLastOpenedBookId();
        if (id == null) {
            id = prefs.get("lastOpenedBookId", null);
        }
        return id;
    }

    public void saveSelectedAuthorId(String authorId) {
        prefs.put("selectedAuthorId", authorId);
    }

    public String getSelectedAuthorId() {
        return prefs.get("selectedAuthorId", null);
    }

    public void saveWindowState(double width, double height) {
        prefs.putDouble("windowWidth", width);
        prefs.putDouble("windowHeight", height);
    }

    public double[] getWindowState() {
        return new double[]{
                prefs.getDouble("windowWidth", 1200),
                prefs.getDouble("windowHeight", 800)
        };
    }

    public void saveSearchQuery(String query) {
        prefs.put("lastSearchQuery", query);
    }

    public String getLastSearchQuery() {
        return prefs.get("lastSearchQuery", "");
    }
}