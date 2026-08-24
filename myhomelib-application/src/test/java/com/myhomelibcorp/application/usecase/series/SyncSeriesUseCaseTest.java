package com.myhomelibcorp.application.usecase.series;

import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SyncSeriesUseCaseTest {

    @Test
    void delegatesSynchronizationToRepository() {
        SeriesRepository repository = mock(SeriesRepository.class);

        new SyncSeriesUseCase(repository).execute();

        verify(repository).syncSeriesFromBooks();
    }
}
