package com.myhomelibcorp.application.usecase.reading;

import com.myhomelibcorp.application.dto.NextInSeriesItemDto;
import com.myhomelibcorp.application.port.out.repository.NextInSeriesQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NextInSeriesService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private final NextInSeriesQueryRepository repository;
    public List<NextInSeriesItemDto> next() { return next(DEFAULT_LIMIT); }
    public List<NextInSeriesItemDto> next(int limit) {
        int safe = Math.max(1, Math.min(MAX_LIMIT, limit));
        return List.copyOf(repository.findNextInSeries(safe));
    }
}
