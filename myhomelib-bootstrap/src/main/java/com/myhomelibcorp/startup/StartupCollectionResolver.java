package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.session.SessionService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Resolves/creates the collection that startup tasks operate on. */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupCollectionResolver {
    private final CollectionRepository collectionRepository;
    private final SessionService sessionService;

    public Collection resolve() {
        List<Collection> collections = collectionRepository.findAll();
        log.info("Знайдено {} колекцій при старті", collections.size());

        if (collections.isEmpty()) {
            String dbPath = AppPaths.librariesDir().resolve(UUID.randomUUID() + ".db").toString();
            Collection created = collectionRepository.save(new Collection(
                    null,
                    "Моя бібліотека",
                    null,
                    dbPath,
                    0,
                    null,
                    null,
                    null,
                    null
            ));
            log.info("Створено стандартну колекцію: id={}, dbFile={}", created.getId(), created.getDbFile());
            return created;
        }

        String lastCollectionId = sessionService.isRestoreEnabled() ? sessionService.getLastCollectionId() : null;
        Collection active = lastCollectionId == null ? collections.getFirst() : collections.stream()
                .filter(collection -> lastCollectionId.equals(collection.getId()))
                .findFirst()
                .orElse(collections.getFirst());

        log.info("Використовуємо колекцію при старті: id={}, name={}, dbFile={}, restored={}",
                active.getId(), active.getName(), active.getDbFile(),
                lastCollectionId != null && lastCollectionId.equals(active.getId()));
        for (Collection collection : collections) {
            log.debug("Колекція startup candidate: {} (id={}, dbFile={})",
                    collection.getName(), collection.getId(), collection.getDbFile());
        }
        return active;
    }
}
