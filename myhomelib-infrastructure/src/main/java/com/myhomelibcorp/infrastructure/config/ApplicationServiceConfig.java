package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.infrastructure.FolderSyncPort;
import com.myhomelibcorp.application.port.out.repository.*;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.collection.*;
import com.myhomelibcorp.application.usecase.collection.AttachHlc2CollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionFromNetworkUseCase;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionPropertiesUseCase;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.catalog.CatalogSourceStatePort;
import com.myhomelibcorp.application.port.out.collection.BookUserStateTransferPort;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import com.myhomelibcorp.application.usecase.collection.CopyBooksBetweenCollectionsUseCase;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.application.usecase.search.DeleteSavedSearchUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.application.usecase.sync.SyncFolderUseCase;
import com.myhomelibcorp.application.usecase.author.UpdateAuthorDescriptionUseCase;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationServiceConfig {

    @Bean
    public CollectionLifecycleService collectionLifecycleService(
            CollectionLifecyclePort collectionLifecyclePort,
            DatabaseMigrationPort databaseMigrationPort,
            CacheInvalidationPort cacheInvalidationPort,
            IndexRebuilder indexRebuilder,
            SearchIndexLifecycle searchIndexLifecycle,
            DomainEventPublisher eventPublisher,
            ExecutorPort executorPort,
            LibraryOperationCoordinator operationCoordinator
    ) {
        return new CollectionLifecycleService(
                collectionLifecyclePort,
                databaseMigrationPort,
                cacheInvalidationPort,
                indexRebuilder,
                searchIndexLifecycle,
                eventPublisher,
                executorPort,
                operationCoordinator
        );
    }

    @Bean
    public SwitchCollectionUseCase switchCollectionUseCase(
            CollectionRepository collectionRepository,
            CollectionLifecycleService collectionLifecycleService,
            LibraryOperationCoordinator operationCoordinator
    ) {
        return new SwitchCollectionUseCase(
                collectionRepository,
                collectionLifecycleService,
                operationCoordinator
        );
    }

    @Bean
    public CreateCollectionUseCase createCollectionUseCase(
            CollectionRepository collectionRepository,
            CollectionLifecycleService collectionLifecycleService,
            com.myhomelibcorp.application.usecase.imports.ImportFileUseCase importFileUseCase,
            com.myhomelibcorp.application.port.out.catalog.CollectionInfoPort collectionInfoPort,
            LibraryOperationCoordinator operationCoordinator,
            com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager storageManager
    ) {
        return new CreateCollectionUseCase(
                collectionRepository,
                collectionLifecycleService,
                importFileUseCase,
                collectionInfoPort,
                operationCoordinator,
                storageManager
        );
    }

    @Bean
    public UpdateCollectionPropertiesUseCase updateCollectionPropertiesUseCase(
            CollectionRepository repository,
            CollectionLifecyclePort lifecyclePort) {
        return new UpdateCollectionPropertiesUseCase(repository, lifecyclePort);
    }

    @Bean
    public AttachHlc2CollectionUseCase attachHlc2CollectionUseCase(LegacyCollectionAttachPort port) {
        return new AttachHlc2CollectionUseCase(port);
    }

    @Bean
    public UpdateCollectionFromNetworkUseCase updateCollectionFromNetworkUseCase(
            RemoteCatalogDownloadPort downloader,
            com.myhomelibcorp.application.usecase.imports.ImportFileUseCase importer,
            CollectionLifecycleService lifecycle,
            CatalogSourceStatePort sourceState,
            SearchIndexer searchIndexer,
            BookQueryRepository bookQueryRepository,
            StatisticsRepository statisticsRepository,
            com.myhomelibcorp.application.port.out.backup.CollectionBackupPort collectionBackupPort,
            LibraryOperationCoordinator operationCoordinator,
            @Value("${app.import.change-tracking-limit:50000}") int changeTrackingLimit) {
        return new UpdateCollectionFromNetworkUseCase(
                downloader, importer, lifecycle, sourceState, searchIndexer, bookQueryRepository, statisticsRepository,
                collectionBackupPort, changeTrackingLimit, operationCoordinator);
    }

    @Bean
    public SyncFolderUseCase syncFolderUseCase(FolderSyncPort folderSyncPort,
                                                LibraryOperationCoordinator operationCoordinator) {
        return new SyncFolderUseCase(folderSyncPort, operationCoordinator);
    }

    @Bean
    public UpdateAuthorDescriptionUseCase updateAuthorDescriptionUseCase(AuthorRepository authors) {
        return new UpdateAuthorDescriptionUseCase(authors);
    }

    @Bean
    public SaveSearchUseCase saveSearchUseCase(SavedSearchRepository savedSearchRepository) {
        return new SaveSearchUseCase(savedSearchRepository);
    }

    @Bean
    public LoadSavedSearchesUseCase loadSavedSearchesUseCase(SavedSearchRepository savedSearchRepository) {
        return new LoadSavedSearchesUseCase(savedSearchRepository);
    }

    @Bean
    public DeleteSavedSearchUseCase deleteSavedSearchUseCase(SavedSearchRepository savedSearchRepository) {
        return new DeleteSavedSearchUseCase(savedSearchRepository);
    }

    @Bean
    public CopyBooksBetweenCollectionsUseCase copyBooksBetweenCollectionsUseCase(
            BookQueryRepository books,
            CollectionRepository collections,
            com.myhomelibcorp.application.port.out.resource.BookResourcePort resources,
            CollectionLifecycleService lifecycle,
            com.myhomelibcorp.application.imports.saver.BookSaver bookSaver,
            BookUserStateTransferPort userStateTransfer,
            com.myhomelibcorp.application.search.SearchIndexSynchronizer searchIndexSynchronizer) {
        return new CopyBooksBetweenCollectionsUseCase(
                books, collections, resources, lifecycle, bookSaver, userStateTransfer, searchIndexSynchronizer);
    }
}