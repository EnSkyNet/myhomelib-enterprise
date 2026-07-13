package com.myhomelibcorp.application.port.out.repository;

public interface SessionRepository {
    void saveLastOpenedBookId(String bookId);  // змінено з Long на String
    String getLastOpenedBookId();              // змінено з Long на String
}