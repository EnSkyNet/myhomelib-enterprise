package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import com.myhomelibcorp.shared.util.AppPaths;

@RequiredArgsConstructor
@Slf4j
public class CreateCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionLifecycleService collectionLifecycleService;
    private final ImportFileUseCase importFileUseCase;

    public Collection execute(CreateCollectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Collection name cannot be empty");
        }

        log.info("📚 Створення нової колекції: {}", request.getName());

        String dbPath = determineDbPath(request);

        // ID = null - репозиторій створить сам
        Collection collection = new Collection(
                null,
                request.getName(),
                request.getRootFolder(),
                dbPath,
                request.getTypeCode(),
                request.getUser(),
                request.getPassword(),
                request.getUrl(),
                request.getNotes()
        );

        log.info("Шлях до БД: {}", dbPath);

        // Зберігаємо в мета-БД
        Collection saved = collectionRepository.save(collection);
        log.info("✅ Колекцію збережено: id={}, name={}", saved.getId(), saved.getName());

        return saved;
    }

    private String determineDbPath(CreateCollectionRequest request) {
        if (request.getDbFile() != null) {
            return request.getDbFile().toString();
        }
        return AppPaths.librariesDir().resolve(java.util.UUID.randomUUID() + ".db").toString();
    }
}