package com.myhomelibcorp.application.port.out.repository;

public interface SessionRepository {
    void saveLastOpenedBookId(String bookId);
    String getLastOpenedBookId();
}