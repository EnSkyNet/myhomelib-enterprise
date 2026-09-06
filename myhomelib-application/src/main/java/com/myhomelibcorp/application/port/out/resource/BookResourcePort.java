package com.myhomelibcorp.application.port.out.resource;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Порт для роботи з ресурсами книг (файли, архіви).
 * Реалізується в інфраструктурному шарі.
 */
public interface BookResourcePort {

    /**
     * Знаходить шлях до файлу книги.
     * @param book книга
     * @return шлях до файлу, якщо знайдено
     */
    Optional<Path> locateBookFile(Book book);

    /**
     * Знаходить шлях до файлу книги за DTO.
     * @param bookDto книга (може бути BookDto або BookListItem)
     * @param fileName ім'я файлу
     * @param folder папка
     * @param collectionRoot коренева папка колекції
     * @param archiveEntry запис в архіві
     * @return шлях до файлу, якщо знайдено
     */
    Optional<Path> locateBookFile(String fileName, String folder, String collectionRoot, String archiveEntry);

    /**
     * Читає дані книги.
     * @param book книга
     * @return InputStream з даними книги, або порожній Optional
     */
    Optional<InputStream> readBookData(Book book);

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
     * Перевіряє, чи файл є архівом (ZIP, FB2ZIP, FBD).
     */
    boolean isArchive(String path);

    /**
     * Отримує список записів в архіві.
     * @param archivePath шлях до архіву
     * @return список імен записів
     */
    List<String> listArchiveEntries(Path archivePath);

    /**
     * Читає запис з архіву.
     * @param archivePath шлях до архіву
     * @param entryName ім'я запису
     * @return InputStream з даними запису, або порожній Optional
     */
    Optional<InputStream> readArchiveEntry(Path archivePath, String entryName);

    /**
     * Шукає перший запис в архіві, що відповідає фільтру.
     * @param archivePath шлях до архіву
     * @param filter фільтр для імен записів
     * @return InputStream з даними запису, або порожній Optional
     */
    Optional<InputStream> findFirstArchiveEntry(Path archivePath, java.util.function.Predicate<String> filter);

    /**
     * Prepares a reversible physical deletion inside an explicitly managed root.
     * Implementations must reject symlinks and paths escaping the canonical managed root.
     * The returned recovery handle keeps the bytes recoverable until catalog state commits.
     */
    StagedDeletion stagePhysicalFileForDeletion(Path path, Path managedRoot, String collectionId, List<BookId> affectedBookIds) throws IOException;

    /** Reversible filesystem phase used by crash-safe local-copy removal. */
    interface StagedDeletion {
        Path originalPath();
        Path recoveryPath();

        /** Catalog mutation committed: release the recovery copy/link. */
        void commit() throws IOException;

        /** Catalog mutation failed: restore the original bytes. Safe to call more than once. */
        void rollback() throws IOException;
    }

    /**
     * Будує повний шлях до файлу з компонентів.
     */
    Path buildFilePath(String root, String folder, String fileName);
}