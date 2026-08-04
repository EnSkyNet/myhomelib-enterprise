package com.myhomelibcorp.application.port.out.repository;

public interface SessionRepository {
    void saveLastOpenedBookId(String collectionId, String bookId);
    String getLastOpenedBookId(String collectionId);

    /**
     * Очищує session state для конкретної колекції.
     */
    default void clearSession(String collectionId) {
        // За замовчуванням нічого не робимо
        // Конкретна реалізація може перевизначити
    }
}