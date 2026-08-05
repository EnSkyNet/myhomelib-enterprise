package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Use Case: створення нової колекції.
 */
@RequiredArgsConstructor
@Slf4j
public class CreateCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionLifecycleService collectionLifecycleService;

    public Collection execute(CreateCollectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Collection name cannot be empty");
        }

        log.info("📚 Створення нової колекції: {}", request.getName());

        // Визначаємо шлях до БД
        String dbPath = determineDbPath(request);

        // Створюємо колекцію
        Collection collection = new Collection(
                UUID.randomUUID().toString(),
                request.getName(),
                request.getRootFolder(),
                dbPath,
                request.getTypeCode(),
                request.getUser(),
                request.getPassword(),
                request.getUrl(),
                request.getNotes()
        );

        // Зберігаємо в мета-БД
        Collection saved = collectionRepository.save(collection);
        log.info("✅ Колекцію створено: id={}, name={}", saved.getId(), saved.getName());

        // Якщо потрібно - активуємо та імпортуємо
        if (request.isImportOnCreate()) {
            try {
                collectionLifecycleService.initializeCollection(saved, request.isCreateIndex());
                log.info("✅ Колекцію активовано та проіндексовано");
            } catch (Exception e) {
                log.error("❌ Помилка активації колекції", e);
                // Не кидаємо виняток - колекція вже створена
            }
        }

        return saved;
    }

    private String determineDbPath(CreateCollectionRequest request) {
        if (request.getDbFile() != null) {
            return request.getDbFile().toString();
        }

        // Стандартний шлях
        String home = System.getProperty("user.home");
        String id = UUID.randomUUID().toString();
        return home + "/.myhomelibcorp/libraries/" + id + ".db";
    }
}