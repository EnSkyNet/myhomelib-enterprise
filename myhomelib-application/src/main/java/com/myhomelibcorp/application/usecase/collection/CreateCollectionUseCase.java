package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.UUID;
import com.myhomelibcorp.shared.util.AppPaths;

/**
 * Use Case: створення нової колекції.
 */
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

        // Активуємо колекцію. Якщо задане джерело (INPX/INP/архів/книга), реально імпортуємо його.
        if (request.isImportOnCreate()) {
            try {
                collectionLifecycleService.initializeCollection(saved, false);
                if (request.getSourcePath() != null && !request.getSourcePath().isBlank()) {
                    Path source = Path.of(request.getSourcePath()).toAbsolutePath().normalize();
                    Path root = request.getRootFolder() != null ? request.getRootFolder().toAbsolutePath().normalize()
                            : (source.getParent() != null ? source.getParent() : Path.of(".").toAbsolutePath().normalize());
                    importFileUseCase.execute(ImportContext.builder()
                            .file(source)
                            .rootDirectory(root)
                            .batchSize(1000)
                            .indexAfterSave(false)
                            .build());
                    log.info("✅ Джерело колекції імпортовано: {}", source);
                }
                if (request.isCreateIndex()) collectionLifecycleService.rebuildSearchIndex();
                log.info("✅ Колекцію активовано та проіндексовано");
            } catch (Exception e) {
                log.error("❌ Помилка створення/імпорту колекції", e);
                throw new IllegalStateException("Колекцію створено, але не вдалося імпортувати джерело: " + e.getMessage(), e);
            }
        }

        return saved;
    }

    private String determineDbPath(CreateCollectionRequest request) {
        if (request.getDbFile() != null) {
            return request.getDbFile().toString();
        }

        // Стандартний шлях
        String id = UUID.randomUUID().toString();
        return AppPaths.librariesDir().resolve(id + ".db").toString();
    }
}