package com.myhomelibcorp.application.port.out.importer;

import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.progress.OperationProgress;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Port for the high-throughput INPX import path. */
public interface FastImportService {

    /**
     * Complete v7.1 INPX import contract. Implementations must honor cancellation, snapshot semantics,
     * progress callbacks and structured operation telemetry; no compatibility overload may silently
     * discard those signals.
     */
    ImportResult importInpx(
            Path file,
            int batchSize,
            Path rootDirectory,
            AtomicBoolean cancelFlag,
            String catalogSourceKey,
            String catalogSourceLocation,
            boolean catalogFullSnapshot,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer,
            String operationId,
            Consumer<OperationProgress> operationProgressListener);
}
