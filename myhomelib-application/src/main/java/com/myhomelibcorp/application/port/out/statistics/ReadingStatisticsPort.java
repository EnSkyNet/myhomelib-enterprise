package com.myhomelibcorp.application.port.out.statistics;

import com.myhomelibcorp.application.dto.ReadingStatisticsDto;

import java.util.Optional;

/**
 * Порт для збереження та завантаження статистики читання.
 * Реалізується в інфраструктурному шарі.
 */
public interface ReadingStatisticsPort {

    /**
     * Зберігає статистику читання.
     * @param stats статистика для збереження
     */
    void save(ReadingStatisticsDto stats);

    /**
     * Завантажує статистику читання для книги.
     * @param bookId ID книги
     * @return статистика, якщо знайдено
     */
    Optional<ReadingStatisticsDto> findByBookId(String bookId);

    /**
     * Видаляє статистику для книги.
     * @param bookId ID книги
     */
    void deleteByBookId(String bookId);

    /**
     * Оновлює прогрес читання для книги.
     * @param bookId ID книги
     * @param percent поточний прогрес
     */
    void updateProgress(String bookId, int percent);
}