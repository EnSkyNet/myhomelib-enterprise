package com.myhomelibcorp.application.port.out.importer;

import java.nio.file.Path;
import java.util.List;

/**
 * Реєстр імпортерів. Використовується для пошуку відповідного імпортера
 * за типом файлу.
 */
public interface ImporterRegistry {

    /**
     * Знайти імпортер, який підтримує даний файл.
     * @param file шлях до файлу
     * @return імпортер
     * @throws IllegalArgumentException якщо підтримуваний імпортер не знайдено
     */
    BookImporterPort findImporter(Path file);

    /**
     * Отримати всі зареєстровані імпортери.
     */
    List<BookImporterPort> getAllImporters();

    /**
     * Отримати список назв підтримуваних форматів.
     */
    List<String> getSupportedFormats();
}