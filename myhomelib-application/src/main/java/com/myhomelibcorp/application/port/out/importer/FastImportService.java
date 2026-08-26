package com.myhomelibcorp.application.port.out.importer;

import com.myhomelibcorp.application.imports.statistics.ImportResult;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Port for the high-throughput INPX import path.
 */
public interface FastImportService {

    long importInpx(Path file, int batchSize, Path rootDirectory);

    default long importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag) {
        return importInpx(file, batchSize, rootDirectory);
    }

    default long importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag,
                            String catalogSourceKey, String catalogSourceLocation) {
        return importInpx(file, batchSize, rootDirectory, cancelFlag);
    }

    /**
     * Rich INPX import contract used by the workspace: progress/status are propagated
     * to infrastructure and full statistics are returned instead of only a book count.
     */
    default ImportResult importInpx(
            Path file,
            int batchSize,
            Path rootDirectory,
            AtomicBoolean cancelFlag,
            String catalogSourceKey,
            String catalogSourceLocation,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer) {
        long imported = importInpx(
                file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation);
        return new ImportResult(imported, 0, 0, 0, 0);
    }
}
