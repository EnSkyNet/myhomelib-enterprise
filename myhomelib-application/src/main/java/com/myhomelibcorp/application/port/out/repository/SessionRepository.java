package com.myhomelibcorp.application.port.out.repository;

public interface SessionRepository {
    void saveLastOpenedBookId(Long bookId);
    Long getLastOpenedBookId();
}