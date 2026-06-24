package com.myhomelibcorp.application.series;

import com.myhomelibcorp.application.port.out.SeriesRepository;
import com.myhomelibcorp.domain.model.series.Series;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadSeriesUseCase {

    private final SeriesRepository seriesRepository;

    public List<Series> loadAll() {
        return seriesRepository.findAll();
    }
}