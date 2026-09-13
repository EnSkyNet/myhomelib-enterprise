package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Restores unfinished full-text indexing work for the active collection after migrations/search startup. */
@Component
@RequiredArgsConstructor
public class ContentIndexingStartupTask implements StartupTask {
    private final ContentIndexingQueueService queueService;

    @Override public String id() { return "ContentIndexingStartupTask"; }
    @Override public StartupFailurePolicy failurePolicy() { return StartupFailurePolicy.BEST_EFFORT; }

    @Override
    public StartupTaskResult execute(StartupContext context) {
        int restored = queueService.restore(context.activeCollection().getId());
        return restored == 0
                ? StartupTaskResult.skipped("no unfinished content-index tasks")
                : StartupTaskResult.success("restored " + restored + " content-index task(s)");
    }
}
