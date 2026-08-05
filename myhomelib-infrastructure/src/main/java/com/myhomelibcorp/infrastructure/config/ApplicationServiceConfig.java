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
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.application.usecase.search.DeleteSavedSearchUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.application.usecase.sync.SyncFolderUseCase;
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
            AuthorRepository authorRepository,
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
                authorRepository,
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
            CollectionLifecycleService collectionLifecycleService
    ) {
        return new CreateCollectionUseCase(
                collectionRepository,
                collectionLifecycleService
        );
    }

    @Bean
    public SyncFolderUseCase syncFolderUseCase(FolderSyncPort folderSyncPort) {
        return new SyncFolderUseCase(folderSyncPort);
    }

    // Додайте в кінець класу:

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
}