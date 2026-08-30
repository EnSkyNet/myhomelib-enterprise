package com.myhomelibcorp.application.session;

import com.myhomelibcorp.domain.event.collection.CollectionOpenedEvent;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.SessionRepository;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
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
    private final ApplicationSettingsPort settings;
    private final Preferences prefs = Preferences.userNodeForPackage(SessionService.class);

    private static final String LAST_COLLECTION_KEY = "lastCollectionId";
    private static final String WORKSPACE_TYPE_PREFIX = "workspaceType_";
    private static final String WORKSPACE_ID_PREFIX = "workspaceId_";

    private String currentCollectionId;
    private String currentBookId;

    @EventListener
    public void onCollectionOpened(CollectionOpenedEvent event) {
        Collection collection = event.getCollection();
        if (collection == null) {
            log.warn("CollectionOpenedEvent отримано без колекції");
            return;
        }

        String newCollectionId = collection.getId();
        log.info("Ініціалізація SessionService для колекції: {} (id: {})", collection.getName(), newCollectionId);

        this.currentCollectionId = newCollectionId;
        prefs.put(LAST_COLLECTION_KEY, newCollectionId);

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

    public boolean isRestoreEnabled() {
        return settings.getBoolean("ui.restoreSession", true);
    }

    public String getLastCollectionId() {
        return prefs.get(LAST_COLLECTION_KEY, null);
    }

    public void saveWorkspaceState(String type, String id) {
        String collectionId = getCurrentCollectionId();
        if (collectionId == null || type == null || type.isBlank()) return;
        prefs.put(WORKSPACE_TYPE_PREFIX + collectionId, type);
        prefs.put(WORKSPACE_ID_PREFIX + collectionId, id == null ? "" : id);
    }

    public WorkspaceState getWorkspaceState() {
        String collectionId = getCurrentCollectionId();
        if (collectionId == null || !isRestoreEnabled()) return null;
        String type = prefs.get(WORKSPACE_TYPE_PREFIX + collectionId, "");
        if (type.isBlank()) return null;
        return new WorkspaceState(type, prefs.get(WORKSPACE_ID_PREFIX + collectionId, ""));
    }

    public void saveWindowState(double width, double height) {
        if (width > 0) prefs.putDouble("windowWidth", width);
        if (height > 0) prefs.putDouble("windowHeight", height);
    }

    public double[] getWindowState() {
        return new double[]{
                prefs.getDouble("windowWidth", 1200),
                prefs.getDouble("windowHeight", 800)
        };
    }

    public record WorkspaceState(String type, String id) {
        public WorkspaceState {
            type = type == null ? "" : type.trim();
            id = id == null ? "" : id;
        }
    }

    public void clearCurrentSession() {
        String collectionId = getCurrentCollectionId();
        if (collectionId == null) {
            return;
        }
        currentBookId = null;
        sessionRepository.clearSession(collectionId);
        log.info("Очищено session для колекції {}", collectionId);
    }
}