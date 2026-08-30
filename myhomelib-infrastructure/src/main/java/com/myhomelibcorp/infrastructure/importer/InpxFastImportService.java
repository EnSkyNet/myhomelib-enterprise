package com.myhomelibcorp.infrastructure.importer;

import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.port.out.importer.FastImportService;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class InpxFastImportService implements FastImportService {

    private final InpxImportPipeline pipeline;

    @Override
    public ImportResult importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag,
                                   String catalogSourceKey, String catalogSourceLocation,
                                   boolean catalogFullSnapshot,
                                   DoubleConsumer progressListener, Consumer<String> statusConsumer,
                                   String operationId, Consumer<OperationProgress> operationProgressListener) {
        log.info("Fast import INPX with structured telemetry: {} (sourceKey: {}, fullSnapshot={})",
                file, catalogSourceKey, catalogFullSnapshot);
        return pipeline.importFileWithResult(
                file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation,
                catalogFullSnapshot, progressListener, statusConsumer, operationId, operationProgressListener);
    }
}
