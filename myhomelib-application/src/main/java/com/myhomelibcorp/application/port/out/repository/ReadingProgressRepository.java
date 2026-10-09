package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.dto.ReadingProgressDto;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public interface ReadingProgressRepository {
    void save(ReadingProgressDto progress);
    Optional<ReadingProgressDto> findByBookId(String bookId);
    void deleteByBookId(String bookId);

    default Map<String, ReadingProgressDto> findByBookIds(Collection<String> bookIds) {
        Map<String, ReadingProgressDto> result = new LinkedHashMap<>();
        if (bookIds == null) return result;
        for (String id : bookIds) {
            if (id == null || id.isBlank()) continue;
            findByBookId(id).ifPresent(value -> result.put(id, value));
        }
        return result;
    }
}
