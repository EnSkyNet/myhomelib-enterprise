package com.myhomelibcorp.application.port.out;

import java.util.List;
import java.util.Map;

/**
 * Порт для роботи з жанрами.
 * UI використовує цей інтерфейс, а реалізація знаходиться в infrastructure.
 */
public interface GenreService {

    /**
     * Отримати назву жанру за його кодом.
     * @param code код жанру (наприклад, "sf_fantasy")
     * @return назва жанру або код, якщо назву не знайдено
     */
    String getGenreName(String code);

    /**
     * Отримати всі назви жанрів (відсортовані за порядком у файлі).
     * @return список назв
     */
    List<String> getAllGenreNames();

    /**
     * Отримати всі жанри у вигляді мапи код -> назва.
     * @return мапа жанрів
     */
    Map<String, String> getAllGenres();

    /**
     * Отримати всі коди жанрів.
     * @return список кодів
     */
    List<String> getAllGenreCodes();
}