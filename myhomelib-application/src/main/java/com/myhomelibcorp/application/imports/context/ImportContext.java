package com.myhomelibcorp.application.imports.context;

import com.myhomelibcorp.application.progress.OperationProgress;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

@Value
@Builder
public class ImportContext {
    Path rootDirectory;
    Path file;
    String archiveEntry;
    /** Stable logical source key (for remote INPX use collection id, never the temp download path). */
    String catalogSourceKey;
    /** Optional diagnostic source location; credentials/query tokens are not persisted by the adapter. */
    String catalogSourceLocation;
    @Builder.Default boolean updateExisting = false;
    @Builder.Default boolean indexAfterSave = true;
    /** Publish one public ImportFinishedEvent for this context. Composite operations disable child events. */
    @Builder.Default boolean publishFinishedEvent = true;
    /** True when the catalog package is a complete snapshot; false for delta/extra updates. */
    @Builder.Default boolean catalogFullSnapshot = true;
    DoubleConsumer progressListener;
    Consumer<String> statusConsumer;
    /** Structured, JavaFX-independent telemetry used by v7.1 progress UI. */
    Consumer<OperationProgress> operationProgressListener;
    String operationId;
    AtomicBoolean cancelFlag;
    @Builder.Default int batchSize = 1000;

    public static ImportContext defaultContext() {
        return ImportContext.builder()
                .updateExisting(false)
                .indexAfterSave(true)
                .batchSize(1000)
                .build();
    }
}