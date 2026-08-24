package com.myhomelibcorp.application.port.out.importer;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Порт для швидкого імпорту великих файлів (INPX).
 * Реалізується в інфраструктурному шарі.
 */
public interface FastImportService {

    /**
     * Виконує швидкий імпорт INPX файлу.
     *
     * @param file          шлях до INPX файлу
     * @param batchSize     розмір батча
     * @param rootDirectory коренева папка колекції (для collectionRoot)
     * @return кількість імпортованих книг
     */
    long importInpx(Path file, int batchSize, Path rootDirectory);

    default long importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag) {
        return importInpx(file, batchSize, rootDirectory);
    }

    /**
     * Stage 6 overload carrying a stable logical catalog source through a downloaded temp file.
     */
    default long importInpx(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag,
                            String catalogSourceKey, String catalogSourceLocation) {
        return importInpx(file, batchSize, rootDirectory, cancelFlag);
    }
}