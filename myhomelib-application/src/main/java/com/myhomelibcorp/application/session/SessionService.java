package com.myhomelibcorp.application.session;

import com.myhomelibcorp.application.event.CollectionOpenedEvent;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
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
    private final CollectionLifecyclePort collectionLifecyclePort;
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

        // Отримуємо поточну колекцію через порт
        Collection currentCollection = collectionLifecyclePort.getCurrentCollection();
        if (currentCollection == null) {
            log.warn("Спроба зберегти книгу без активної колекції");
            return;
        }

        String collectionId = currentCollection.getId();
        this.currentCollectionId = collectionId;
        this.currentBookId = bookId;

        sessionRepository.saveLastOpenedBookId(collectionId, bookId);
        log.debug("Збережено останню книгу для колекції {}: {}", collectionId, bookId);
    }

    public String getLastOpenedBookId() {
        Collection currentCollection = collectionLifecyclePort.getCurrentCollection();
        if (currentCollection == null) {
            log.warn("Спроба отримати книгу без активної колекції");
            return null;
        }

        String collectionId = currentCollection.getId();
        this.currentCollectionId = collectionId;

        if (currentBookId != null) {
            return currentBookId;
        }

        String bookId = sessionRepository.getLastOpenedBookId(collectionId);
        if (bookId != null) {
            currentBookId = bookId;
        }
        return bookId;
    }

    public String getCurrentCollectionId() {
        if (currentCollectionId == null) {
            Collection current = collectionLifecyclePort.getCurrentCollection();
            if (current != null) {
                currentCollectionId = current.getId();
            }
        }
        return currentCollectionId;
    }

    public void saveSelectedAuthorId(String authorId) {
        String collectionId = getCurrentCollectionId();
        if (collectionId == null) {
            log.warn("Спроба зберегти автора без активної колекції");
            return;
        }
        prefs.put("selectedAuthorId_" + collectionId, authorId);
    }

    public String getSelectedAuthorId() {
        String collectionId = getCurrentCollectionId();
        if (collectionId == null) {
            return null;
        }
        return prefs.get("selectedAuthorId_" + collectionId, null);
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
        String collectionId = getCurrentCollectionId();
        if (collectionId == null) {
            log.warn("Спроба зберегти пошук без активної колекції");
            return;
        }
        prefs.put("lastSearchQuery_" + collectionId, query);
    }

    public String getLastSearchQuery() {
        String collectionId = getCurrentCollectionId();
        if (collectionId == null) {
            return "";
        }
        return prefs.get("lastSearchQuery_" + collectionId, "");
    }

    public void clearCurrentSession() {
        String collectionId = getCurrentCollectionId();
        if (collectionId == null) {
            return;
        }
        currentBookId = null;
        String prefKey = "lastOpenedBookId_" + collectionId;
        prefs.remove(prefKey);
        log.info("Очищено session для колекції {}", collectionId);
    }
}