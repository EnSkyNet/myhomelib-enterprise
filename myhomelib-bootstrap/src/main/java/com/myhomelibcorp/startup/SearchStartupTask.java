package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.service.CollectionLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchStartupTask implements StartupTask {
    private final CollectionLifecycleService collectionLifecycleService;

    @Override public String id() { return "SearchStartupTask"; }
    @Override public StartupFailurePolicy failurePolicy() { return StartupFailurePolicy.BEST_EFFORT; }

    @Override
    public StartupTaskResult execute(StartupContext context) {
        if (context.reusableSearchIndex()) {
            return StartupTaskResult.skipped("per-collection Lucene index is reusable");
        }

        var rebuild = collectionLifecycleService.rebuildSearchIndexAsync();
        rebuild.whenComplete((ignored, failure) -> {
            if (failure != null) {
                log.error("Background startup Lucene rebuild failed; SQLite remains authoritative", failure);
            }
        });
        return StartupTaskResult.success("background Lucene rebuild scheduled");
    }
}
