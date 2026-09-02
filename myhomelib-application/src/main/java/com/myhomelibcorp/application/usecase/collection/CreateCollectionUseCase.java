package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.catalog.CollectionInfoPort;
import com.myhomelibcorp.application.catalog.collectioninfo.CollectionSourceProperties;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Files;
import com.myhomelibcorp.shared.util.AppPaths;

@RequiredArgsConstructor
@Slf4j
public class CreateCollectionUseCase {

    private final CollectionRepository collectionRepository;
    private final CollectionLifecycleService collectionLifecycleService;
    private final ImportFileUseCase importFileUseCase;
    private final CollectionInfoPort collectionInfoPort;
    private final LibraryOperationCoordinator operationCoordinator;
    private final CollectionStorageManager storageManager;

    public Collection execute(CreateCollectionRequest request) {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.CREATE)) {
            return executeLocked(request);
        }
    }

    private Collection executeLocked(CreateCollectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Collection name cannot be empty");
        }

        Path source = validateSourceIfRequested(request);
        Collection previous = collectionLifecycleService.getCurrentCollection();

        log.info("📚 Створення нової колекції: {}", request.getName());

        String dbPath = determineDbPath(request);
        CollectionSourceProperties sourceProperties = sourceProperties(request);
        String effectiveUrl = firstNonBlank(request.getUrl(), sourceProperties == null ? null : sourceProperties.url());
        String effectiveNotes = firstNonBlankPreserve(request.getNotes(), sourceProperties == null ? null : sourceProperties.notes());
        String effectiveScript = firstNonBlankPreserve(request.getConnectionScript(), sourceProperties == null ? null : sourceProperties.connectionScript());

        Collection collection = new Collection(
                null,
                request.getName(),
                request.getRootFolder(),
                dbPath,
                request.getTypeCode(),
                request.getUser(),
                request.getPassword(),
                effectiveUrl,
                effectiveNotes,
                effectiveScript
        );

        log.info("Шлях до БД: {}", dbPath);
        Collection saved = collectionRepository.save(collection);
        log.info("✅ Колекцію збережено: id={}, name={}", saved.getId(), saved.getName());

        boolean initialized = false;
        try {
            if (source != null) {
                Path root = saved.getRootFolder() != null ? saved.getRootFolder() : source.getParent();
                collectionLifecycleService.initializeCollection(saved, false);
                initialized = true;

                var context = ImportContext.builder()
                        .file(source)
                        .rootDirectory(root)
                        .updateExisting(false)
                        .indexAfterSave(false)
                        .batchSize(1000);
                String lower = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".inpx") || lower.endsWith(".inp")) {
                    context.catalogSourceKey(CatalogSourceIdentity.localInpx(source, root))
                            .catalogSourceLocation(source.toString());
                }
                importFileUseCase.execute(context.build());
                if (request.isCreateIndex()) {
                    collectionLifecycleService.rebuildSearchIndex();
                }
            }
            return saved;
        } catch (RuntimeException creationFailure) {
            rollbackFailedCreation(saved, previous, initialized, creationFailure);
            throw creationFailure;
        }
    }

    private Path validateSourceIfRequested(CreateCollectionRequest request) {
        if (!request.isImportOnCreate() || request.getSourcePath() == null || request.getSourcePath().isBlank()) {
            return null;
        }
        Path source = Path.of(request.getSourcePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Файл джерела не існує: " + source);
        }
        return source;
    }

    private void rollbackFailedCreation(Collection failed, Collection previous, boolean initialized, RuntimeException original) {
        try {
            if (initialized) {
                if (previous != null) {
                    collectionLifecycleService.initializeCollection(previous, true);
                } else {
                    collectionLifecycleService.closeCollection();
                }
            }
        } catch (RuntimeException restoreFailure) {
            original.addSuppressed(restoreFailure);
            log.error("Не вдалося відновити попередню колекцію після невдалого створення; metadata залишено для recovery",
                    restoreFailure);
            return;
        }

        try {
            storageManager.closeCollection(failed);
            storageManager.deletePhysicalFiles(failed);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
            log.error("Не вдалося очистити фізичні ресурси невдалої колекції {}; metadata залишено для recovery",
                    failed.getId(), cleanupFailure);
            return;
        }

        if (failed.getId() != null) {
            collectionRepository.deleteById(failed.getId());
        }
        log.warn("Невдале створення колекції {} повністю відкочено", failed.getName());
    }

    private CollectionSourceProperties sourceProperties(CreateCollectionRequest request) {
        if (request.getSourcePath() == null || request.getSourcePath().isBlank()) return null;
        return collectionInfoPort.read(Path.of(request.getSourcePath()).toAbsolutePath().normalize()).orElse(null);
    }

    private static String firstNonBlank(String local, String source) {
        return local != null && !local.isBlank() ? local.trim() : source;
    }

    private static String firstNonBlankPreserve(String local, String source) {
        return local != null && !local.isBlank() ? local : source;
    }

    private String determineDbPath(CreateCollectionRequest request) {
        if (request.getDbFile() != null) {
            return request.getDbFile().toString();
        }
        return AppPaths.librariesDir().resolve(java.util.UUID.randomUUID() + ".db").toString();
    }
}