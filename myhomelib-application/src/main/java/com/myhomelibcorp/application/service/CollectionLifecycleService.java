package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
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
    private final AuthorRepository authorRepository;
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
            return;
        }

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
            throw new RuntimeException("Не вдалося ініціалізувати колекцію: " + e.getMessage(), e);
        } finally {
            isInitializing.set(false);
        }
    }

    /**
     * Завантажує кеші словників.
     */
    private void loadDictionaries() {
        log.info("📚 Завантаження кешів словників");
        try {
            dictionaryCachePort.loadAuthors(authorRepository.findAll());
            dictionaryCachePort.loadGenres(genreRepository.findAll());
            dictionaryCachePort.loadSeries(seriesRepository.findAll());
            dictionaryCachePort.loadGroups(groupRepository.findAll());
            log.info("✅ Кеші словників завантажено");
        } catch (Exception e) {
            log.error("❌ Помилка завантаження кешів словників", e);
        }
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