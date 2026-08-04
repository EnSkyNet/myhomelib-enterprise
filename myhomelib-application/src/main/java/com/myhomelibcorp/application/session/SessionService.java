package com.myhomelibcorp.application.session;

import com.myhomelibcorp.application.event.CollectionOpenedEvent;
import com.myhomelibcorp.application.port.out.repository.SessionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.prefs.Preferences;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final SessionRepository sessionRepository;
    private final Preferences prefs = Preferences.userNodeForPackage(SessionService.class);

    private String currentCollectionId;
    private String currentBookId;

    @EventListener
    public void onCollectionOpened(CollectionOpenedEvent event) {
        Collection collection = event.collection();
        if (collection == null) {
            log.warn("CollectionOpenedEvent отримано без колекції");
            return;
        }

        String newCollectionId = collection.getId();
        log.info("Ініціалізація SessionService для колекції: {} (id: {})", collection.getName(), newCollectionId);

        this.currentCollectionId = newCollectionId;

        // Завантажуємо останню книгу для цієї колекції
        String lastBookId = sessionRepository.getLastOpenedBookId(newCollectionId);
        if (lastBookId != null && !lastBookId.isEmpty()) {
            this.currentBookId = lastBookId;
            log.debug("Відновлено останню книгу для колекції {}: {}", collection.getName(), lastBookId);
        } else {
            this.currentBookId = null;
            log.debug("Немає збереженої книги для колекції {}", collection.getName());
        }
    }

    public void saveLastOpenedBookId(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            return;
        }
        if (currentCollectionId == null) {
            log.warn("Спроба зберегти книгу без активної колекції");
            return;
        }

        this.currentBookId = bookId;
        sessionRepository.saveLastOpenedBookId(currentCollectionId, bookId);
        log.debug("Збережено останню книгу для колекції {}: {}", currentCollectionId, bookId);
    }

    public String getLastOpenedBookId() {
        if (currentCollectionId == null) {
            log.warn("Спроба отримати книгу без активної колекції");
            return null;
        }
        // Якщо в кеші є, повертаємо звідти
        if (currentBookId != null) {
            return currentBookId;
        }
        // Інакше запитуємо з репозиторію
        String bookId = sessionRepository.getLastOpenedBookId(currentCollectionId);
        if (bookId != null) {
            currentBookId = bookId;
        }
        return bookId;
    }

    public String getCurrentCollectionId() {
        return currentCollectionId;
    }

    public void saveSelectedAuthorId(String authorId) {
        if (currentCollectionId == null) {
            log.warn("Спроба зберегти автора без активної колекції");
            return;
        }
        prefs.put("selectedAuthorId_" + currentCollectionId, authorId);
    }

    public String getSelectedAuthorId() {
        if (currentCollectionId == null) {
            return null;
        }
        return prefs.get("selectedAuthorId_" + currentCollectionId, null);
    }

    public void saveWindowState(double width, double height) {
        // Window state не залежить від колекції
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
        if (currentCollectionId == null) {
            log.warn("Спроба зберегти пошук без активної колекції");
            return;
        }
        prefs.put("lastSearchQuery_" + currentCollectionId, query);
    }

    public String getLastSearchQuery() {
        if (currentCollectionId == null) {
            return "";
        }
        return prefs.get("lastSearchQuery_" + currentCollectionId, "");
    }

    /**
     * Очищує всі дані сесії для поточної колекції.
     * Використовує репозиторій для очищення.
     */
    public void clearCurrentSession() {
        if (currentCollectionId == null) {
            return;
        }
        currentBookId = null;
        // Використовуємо репозиторій для очищення (потрібно додати метод в інтерфейс)
        // Або просто очищаємо Preferences
        String prefKey = "lastOpenedBookId_" + currentCollectionId;
        prefs.remove(prefKey);
        log.info("Очищено session для колекції {}", currentCollectionId);
    }
}