package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.infrastructure.FolderSyncPort;
import com.myhomelibcorp.application.port.out.repository.*;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.collection.CreateCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.AttachHlc2CollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionFromNetworkUseCase;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionPropertiesUseCase;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import com.myhomelibcorp.application.usecase.collection.CopyBooksBetweenCollectionsUseCase;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.application.usecase.search.DeleteSavedSearchUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.application.usecase.sync.SyncFolderUseCase;
import com.myhomelibcorp.application.usecase.author.UpdateAuthorDescriptionUseCase;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationServiceConfig {

    @Bean
    public CollectionLifecycleService collectionLifecycleService(
            CollectionLifecyclePort collectionLifecyclePort,
            DatabaseMigrationPort databaseMigrationPort,
            CacheInvalidationPort cacheInvalidationPort,
            DictionaryCachePort dictionaryCachePort,
            GenreRepository genreRepository,
            SeriesRepository seriesRepository,
            GroupRepository groupRepository,
            IndexRebuilder indexRebuilder,
            DomainEventPublisher eventPublisher
    ) {
        return new CollectionLifecycleService(
                collectionLifecyclePort,
                databaseMigrationPort,
                cacheInvalidationPort,
                dictionaryCachePort,
                genreRepository,
                seriesRepository,
                groupRepository,
                indexRebuilder,
                eventPublisher
        );
    }

    @Bean
    public SwitchCollectionUseCase switchCollectionUseCase(
            CollectionRepository collectionRepository,
            CollectionLifecycleService collectionLifecycleService
    ) {
        return new SwitchCollectionUseCase(
                collectionRepository,
                collectionLifecycleService
        );
    }

    @Bean
    public CreateCollectionUseCase createCollectionUseCase(
            CollectionRepository collectionRepository,
            CollectionLifecycleService collectionLifecycleService,
            com.myhomelibcorp.application.usecase.imports.ImportFileUseCase importFileUseCase
    ) {
        return new CreateCollectionUseCase(
                collectionRepository,
                collectionLifecycleService,
                importFileUseCase
        );
    }

    @Bean
    public UpdateCollectionPropertiesUseCase updateCollectionPropertiesUseCase(CollectionRepository repository) {
        return new UpdateCollectionPropertiesUseCase(repository);
    }

    @Bean
    public AttachHlc2CollectionUseCase attachHlc2CollectionUseCase(LegacyCollectionAttachPort port) {
        return new AttachHlc2CollectionUseCase(port);
    }

    @Bean
    public UpdateCollectionFromNetworkUseCase updateCollectionFromNetworkUseCase(
            RemoteCatalogDownloadPort downloader,
            com.myhomelibcorp.application.usecase.imports.ImportFileUseCase importer,
            CollectionLifecycleService lifecycle) {
        return new UpdateCollectionFromNetworkUseCase(downloader, importer, lifecycle);
    }

    @Bean
    public SyncFolderUseCase syncFolderUseCase(FolderSyncPort folderSyncPort) {
        return new SyncFolderUseCase(folderSyncPort);
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
            com.myhomelibcorp.application.usecase.imports.ImportFileUseCase importer) {
        return new CopyBooksBetweenCollectionsUseCase(books, collections, resources, lifecycle, importer);
    }
}