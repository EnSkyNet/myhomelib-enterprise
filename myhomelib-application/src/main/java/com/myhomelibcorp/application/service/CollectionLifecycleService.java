package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.domain.event.collection.CollectionOpenedEvent;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервіс для управління життєвим циклом колекції.
 * Використовує порти для роботи з інфраструктурою.
 */
@RequiredArgsConstructor
@Slf4j
public class CollectionLifecycleService {

    private final CollectionLifecyclePort collectionLifecyclePort;
    private final DatabaseMigrationPort databaseMigrationPort;
    private final CacheInvalidationPort cacheInvalidationPort;
    private final DictionaryCachePort dictionaryCachePort;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
    private final GroupRepository groupRepository;
    private final IndexRebuilder indexRebuilder;
    private final DomainEventPublisher eventPublisher;

    private final AtomicBoolean isInitializing = new AtomicBoolean(false);

    /**
     * Повна ініціалізація колекції: переключення, міграція, кеші, індекс.
     */
    public void initializeCollection(Collection collection, boolean rebuildIndex) {
        if (!isInitializing.compareAndSet(false, true)) {
            log.warn("Ініціалізація колекції вже виконується");
            throw new IllegalStateException("Інше переключення колекції вже виконується");
        }

        Collection previous = collectionLifecyclePort.getCurrentCollection();
        boolean changedCollection = previous == null || previous.getId() == null
                || collection.getId() == null || !previous.getId().equals(collection.getId());
        try {
            log.info("🚀 Початок ініціалізації колекції: {}", collection.getName());

            // 1. Переключаємо колекцію
            collectionLifecyclePort.switchToCollection(collection);

            // 2. Виконуємо міграції
            int migrations = databaseMigrationPort.migrateCurrentCollection();
            if (migrations > 0) {
                log.info("✅ Виконано {} міграцій", migrations);
            }

            // 3. Очищуємо кеші
            cacheInvalidationPort.invalidateAll();

            // 4. Завантажуємо кеші словників
            loadDictionaries();

            // 5. Перебудовуємо індекс (якщо потрібно)
            if (rebuildIndex) {
                indexRebuilder.rebuildIndex();
                log.info("✅ Індекс перебудовано. Проіндексовано {} документів",
                        indexRebuilder.getIndexedDocumentCount());
            }

            // 6. Публікуємо доменну подію
            eventPublisher.publish(new CollectionOpenedEvent(collection));

            log.info("✅ Ініціалізацію колекції {} завершено", collection.getName());

        } catch (Exception e) {
            log.error("❌ Помилка ініціалізації колекції: {}", e.getMessage(), e);
            if (changedCollection) {
                restorePreviousCollection(previous);
            }
            throw new RuntimeException("Не вдалося ініціалізувати колекцію: " + e.getMessage(), e);
        } finally {
            isInitializing.set(false);
        }
    }

    /** Best-effort rollback after a failed migration/cache/index initialization. */
    private void restorePreviousCollection(Collection previous) {
        try {
            if (previous == null) {
                collectionLifecyclePort.closeCurrentCollection();
                cacheInvalidationPort.invalidateAll();
                log.warn("Невдалу першу колекцію закрито після помилки ініціалізації");
                return;
            }
            collectionLifecyclePort.switchToCollection(previous);
            databaseMigrationPort.migrateCurrentCollection();
            cacheInvalidationPort.invalidateAll();
            loadDictionaries();
            log.warn("Після помилки відновлено попередню колекцію: {}", previous.getName());
        } catch (Exception rollbackError) {
            log.error("❌ Не вдалося відновити попередню колекцію після помилки ініціалізації", rollbackError);
        }
    }

    /**
     * Завантажує кеші словників.
     */
    private void loadDictionaries() {
        log.info("📚 Завантаження кешів словників");
        try {
            // Authors are repository-backed and loaded per initial/search; never preload the full catalogue.
            dictionaryCachePort.loadGenres(genreRepository.findAll());
            dictionaryCachePort.loadSeries(seriesRepository.findAll());
            dictionaryCachePort.loadGroups(groupRepository.findAll());
            log.info("✅ Кеші словників завантажено");
        } catch (Exception e) {
            log.error("❌ Помилка завантаження кешів словників", e);
        }
    }

    /** Rebuilds the search index for the currently active collection. */
    public void rebuildSearchIndex() {
        indexRebuilder.rebuildIndex();
        log.info("✅ Індекс перебудовано. Проіндексовано {} документів", indexRebuilder.getIndexedDocumentCount());
    }

    /**
     * Закриває поточну колекцію.
     */
    public void closeCollection() {
        collectionLifecyclePort.closeCurrentCollection();
        cacheInvalidationPort.invalidateAll();
        log.info("Колекцію закрито");
    }

    /**
     * Отримує поточну колекцію.
     */
    public Collection getCurrentCollection() {
        return collectionLifecyclePort.getCurrentCollection();
    }

    /** Оновлює metadata активної колекції без закриття/відкриття SQLite. */
    public void updateCurrentCollection(Collection collection) {
        collectionLifecyclePort.updateCurrentCollection(collection);
    }

    /**
     * Перевіряє, чи колекція готова до роботи.
     */
    public boolean isCollectionReady() {
        return collectionLifecyclePort.isCollectionReady();
    }

    /**
     * Перевіряє, чи виконується ініціалізація.
     */
    public boolean isInitializing() {
        return isInitializing.get();
    }
}