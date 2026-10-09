package com.myhomelibcorp.application.usecase.reading;

import com.myhomelibcorp.application.dto.NextInSeriesItemDto;
import com.myhomelibcorp.application.port.out.repository.NextInSeriesQueryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NextInSeriesServiceTest {
    @Test
    void limitIsBoundedAndResultIsCopied() {
        NextInSeriesQueryRepository repository = mock(NextInSeriesQueryRepository.class);
        when(repository.findNextInSeries(50)).thenReturn(List.of(new NextInSeriesItemDto("b", "Book", "Series", 2)));
        NextInSeriesService service = new NextInSeriesService(repository);

        assertThat(service.next(500)).extracting(NextInSeriesItemDto::bookId).containsExactly("b");
        verify(repository).findNextInSeries(50);
    }
}
