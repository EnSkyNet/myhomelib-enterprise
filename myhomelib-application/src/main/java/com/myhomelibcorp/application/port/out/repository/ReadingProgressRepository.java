package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.dto.ReadingProgressDto;

import java.util.Optional;

public interface ReadingProgressRepository {
    void save(ReadingProgressDto progress);
    Optional<ReadingProgressDto> findByBookId(String bookId);
    void deleteByBookId(String bookId);
}