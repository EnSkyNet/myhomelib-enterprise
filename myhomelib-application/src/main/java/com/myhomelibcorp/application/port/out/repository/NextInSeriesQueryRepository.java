package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.dto.NextInSeriesItemDto;
import java.util.List;

public interface NextInSeriesQueryRepository {
    List<NextInSeriesItemDto> findNextInSeries(int limit);
}
