package com.myhomelibcorp.startup;

import com.myhomelibcorp.infrastructure.collection.CollectionStartupRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecoveryStartupTask implements StartupTask {
    private final CollectionStartupRecoveryService recoveryService;

    @Override public String id() { return "RecoveryStartupTask"; }
    @Override public StartupFailurePolicy failurePolicy() { return StartupFailurePolicy.REQUIRED; }

    @Override
    public StartupTaskResult execute(StartupContext context) {
        recoveryService.recoverBeforeOpen(context.activeCollection());
        return StartupTaskResult.success("filesystem recovery checked before SQLite open");
    }
}
