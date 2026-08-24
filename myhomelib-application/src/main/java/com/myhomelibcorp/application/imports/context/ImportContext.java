package com.myhomelibcorp.application.imports.context;

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
    DoubleConsumer progressListener;
    Consumer<String> statusConsumer; // додано
    AtomicBoolean cancelFlag;
    @Builder.Default int batchSize = 5000;

    public static ImportContext defaultContext() {
        return ImportContext.builder()
                .updateExisting(false)
                .indexAfterSave(true)
                .batchSize(500)
                .build();
    }
}