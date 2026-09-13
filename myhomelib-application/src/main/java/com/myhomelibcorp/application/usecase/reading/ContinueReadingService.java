package com.myhomelibcorp.application.usecase.reading;

import com.myhomelibcorp.application.dto.ContinueReadingItemDto;
import com.myhomelibcorp.application.port.out.repository.ContinueReadingRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Application boundary for the cross-device Continue Reading shelf. */
@Service
public final class ContinueReadingService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private final ContinueReadingRepository repository;

    public ContinueReadingService(ContinueReadingRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<ContinueReadingItemDto> recent() {
        return recent(DEFAULT_LIMIT);
    }

    public List<ContinueReadingItemDto> recent(int limit) {
        int safe = Math.max(1, Math.min(MAX_LIMIT, limit));
        return List.copyOf(repository.findActive(safe));
    }
}
