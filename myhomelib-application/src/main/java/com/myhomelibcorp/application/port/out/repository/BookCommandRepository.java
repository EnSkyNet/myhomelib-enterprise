package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

public interface BookCommandRepository {
    Book save(Book book);
    void saveBatch(List<Book> books);
    void deleteById(BookId id);
    void updateRate(BookId bookId, int rate);
    void updateProgress(BookId bookId, int progress);
    void updateStorage(BookId bookId, String collectionRoot, String folder, String fileName, String archiveEntry, boolean local);
    void markStorageMissing(BookId bookId);

    /** Selects one artifact as the preferred operational representation for the logical book. */
    void selectPreferredArtifact(BookId bookId, String artifactId);

    /** Upserts one physical representation and optionally makes it the preferred projection. */
    void upsertArtifact(BookId bookId, BookArtifact artifact, boolean makePreferred);

    /** Repairs the v7.1 regression where remote catalog cache was persisted as a book root. */
    int repairTransientRemoteStorageRoots(String permanentRoot);

    // НОВІ БАТЧ-МЕТОДИ
    void updateRateBatch(List<BookId> bookIds, int rate);
    void updateProgressBatch(List<BookId> bookIds, int progress);
}