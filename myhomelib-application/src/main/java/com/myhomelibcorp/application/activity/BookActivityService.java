package com.myhomelibcorp.application.activity;

import com.myhomelibcorp.application.port.out.activity.BookActivityQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BookActivityService {
    private final BookActivityQueryPort queryPort;

    public Map<String, BookActivitySummary> summarize(Collection<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Map.of();
        List<String> ids = bookIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, BookActivitySummary> loaded = queryPort.summarize(ids);
        if (loaded == null) loaded = Map.of();
        LinkedHashMap<String, BookActivitySummary> result = new LinkedHashMap<>();
        for (String id : ids) result.put(id, loaded.getOrDefault(id, BookActivitySummary.empty(id)));
        return Map.copyOf(result);
    }
}
