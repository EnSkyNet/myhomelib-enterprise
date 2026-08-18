package com.myhomelibcorp.application.port.out.resource;

import com.myhomelibcorp.application.dto.BookDto;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Порт для роботи з ресурсами книг (файли, архіви).
 * Реалізується в інфраструктурному шарі.
 * Використовується Reader-сервісами для читання даних книг.
 */
public interface ReaderBookResourcePort {

    /**
     * Читає дані книги.
     * @param bookDto книга
     * @return InputStream з даними книги, або порожній Optional
     */
    Optional<InputStream> readBookData(BookDto bookDto);

    /**
     * Читає дані книги за параметрами.
     * @param fileName ім'я файлу
     * @param folder папка
     * @param collectionRoot коренева папка колекції
     * @param archiveEntry запис в архіві
     * @return InputStream з даними книги, або порожній Optional
     */
    Optional<InputStream> readBookData(String fileName, String folder, String collectionRoot, String archiveEntry);

    /**
     * Знаходить шлях до файлу книги.
     * @param bookDto книга
     * @return шлях до файлу, якщо знайдено
     */
    Optional<Path> locateBookFile(BookDto bookDto);

    /**
     * Знаходить шлях до файлу книги за параметрами.
     * @param fileName ім'я файлу
     * @param folder папка
     * @param collectionRoot коренева папка колекції
     * @param archiveEntry запис в архіві
     * @return шлях до файлу, якщо знайдено
     */
    Optional<Path> locateBookFile(String fileName, String folder, String collectionRoot, String archiveEntry);

    /**
     * Перевіряє, чи файл є архівом (ZIP, FB2ZIP, FBD).
     */
    boolean isArchive(String path);

    /**
     * Будує повний шлях до файлу з компонентів.
     */
    Path buildFilePath(String root, String folder, String fileName);
}