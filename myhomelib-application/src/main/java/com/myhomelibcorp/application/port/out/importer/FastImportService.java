package com.myhomelibcorp.application.port.out.importer;

import java.nio.file.Path;

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
}