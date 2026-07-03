package com.myhomelibcorp.application.usecase.series;

import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadSeriesUseCase {
    private final SeriesRepository seriesRepository;

    public List<String> execute() {
        return seriesRepository.getAllSeriesNames();
    }
}