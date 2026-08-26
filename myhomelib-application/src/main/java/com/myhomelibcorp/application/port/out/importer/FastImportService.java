package com.myhomelibcorp.application.port.out.importer;

import com.myhomelibcorp.application.imports.statistics.ImportResult;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Port for the high-throughput INPX import path. */
public interface FastImportService {

    long importInpx(Path file, int batchSize, Path rootDirectory);

    default long importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag) {
        return importInpx(file, batchSize, rootDirectory);
    }

    default long importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag,
                            String catalogSourceKey, String catalogSourceLocation) {
        return importInpx(file, batchSize, rootDirectory, cancelFlag);
    }

    default ImportResult importInpx(
            Path file,
            int batchSize,
            Path rootDirectory,
            AtomicBoolean cancelFlag,
            String catalogSourceKey,
            String catalogSourceLocation,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer) {
        return importInpx(file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation,
                true, progressListener, statusConsumer);
    }

    /**
     * Rich INPX import contract. {@code catalogFullSnapshot=false} is critical for MyHomeLib
     * extra/delta packages: books absent from a delta must not be marked deleted.
     */
    default ImportResult importInpx(
            Path file,
            int batchSize,
            Path rootDirectory,
            AtomicBoolean cancelFlag,
            String catalogSourceKey,
            String catalogSourceLocation,
            boolean catalogFullSnapshot,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer) {
        long imported = importInpx(file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation);
        return new ImportResult(imported, 0, 0, 0, 0);
    }
}
