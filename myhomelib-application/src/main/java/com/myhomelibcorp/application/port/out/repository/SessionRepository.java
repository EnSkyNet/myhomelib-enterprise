package com.myhomelibcorp.application.port.out.repository;

public interface SessionRepository {
    void saveLastOpenedBookId(String collectionId, String bookId);
    String getLastOpenedBookId(String collectionId);

    /** Очищує session state для конкретної колекції. */
    void clearSession(String collectionId);
}