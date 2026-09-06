package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.infrastructure.importer.inpx.InpxImporter;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MigrationStartupTask implements StartupTask {
    private final SwitchCollectionUseCase switchCollectionUseCase;
    private final ApplicationState applicationState;
    private final InpxImporter inpxImporter;
    private final CollectionLifecycleService collectionLifecycleService;

    @Override public String id() { return "MigrationStartupTask"; }
    @Override public StartupFailurePolicy failurePolicy() { return StartupFailurePolicy.REQUIRED; }

    @Override
    public StartupTaskResult execute(StartupContext context) {
        try {
            SwitchCollectionUseCase.SwitchResult result =
                    switchCollectionUseCase.executeWithStatus(context.activeCollection(), false);
            context.activeCollection(result.collection());
            context.reusableSearchIndex(result.reusableSearchIndex());
            applicationState.setCurrentLibraryCollection(result.collection());
            inpxImporter.initialize();
            return StartupTaskResult.success(result.reusableSearchIndex()
                    ? "collection migrated; reusable search index detected"
                    : "collection migrated; search rebuild required");
        } catch (RuntimeException failure) {
            // Startup has no previous live collection to continue with. If a post-switch component
            // (for example the importer dictionary cache) fails, do not leave a half-started DB open.
            try {
                collectionLifecycleService.closeCollection();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
