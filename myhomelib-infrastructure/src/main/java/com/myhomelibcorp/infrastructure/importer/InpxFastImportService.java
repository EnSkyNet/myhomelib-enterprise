package com.myhomelibcorp.infrastructure.importer;

import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.port.out.importer.FastImportService;
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
    public long importInpx(Path file, int batchSize, Path rootDirectory) {
        log.info("Fast import INPX: {} (root: {})", file, rootDirectory);
        return pipeline.importFile(file, batchSize, rootDirectory);
    }

    @Override
    public long importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag) {
        log.info("Fast import INPX with cancellation: {} (root: {})", file, rootDirectory);
        return pipeline.importFile(file, batchSize, rootDirectory, cancelFlag);
    }

    @Override
    public long importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag,
                           String catalogSourceKey, String catalogSourceLocation) {
        log.info("Fast import INPX with catalog source: {} (root: {}, sourceKey: {})",
                file, rootDirectory, catalogSourceKey);
        return pipeline.importFile(file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation);
    }

    @Override
    public ImportResult importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag,
                                   String catalogSourceKey, String catalogSourceLocation,
                                   DoubleConsumer progressListener, Consumer<String> statusConsumer) {
        return importInpx(file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation,
                true, progressListener, statusConsumer);
    }

    @Override
    public ImportResult importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag,
                                   String catalogSourceKey, String catalogSourceLocation,
                                   boolean catalogFullSnapshot,
                                   DoubleConsumer progressListener, Consumer<String> statusConsumer) {
        log.info("Fast import INPX with progress: {} (root: {}, sourceKey: {}, fullSnapshot={})",
                file, rootDirectory, catalogSourceKey, catalogFullSnapshot);
        return pipeline.importFileWithResult(
                file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation,
                catalogFullSnapshot, progressListener, statusConsumer);
    }
}
