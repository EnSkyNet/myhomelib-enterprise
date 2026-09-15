package com.myhomelibcorp.application.activity;

import com.myhomelibcorp.application.port.out.activity.BookActivityQueryPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookActivityServiceTest {
    @Test
    void normalizesIdsAndFillsMissingSummaries() {
        BookActivityQueryPort port = mock(BookActivityQueryPort.class);
        when(port.summarize(List.of("a", "b"))).thenReturn(Map.of(
                "a", new BookActivitySummary("a", 2, 3, 4)));
        BookActivityService service = new BookActivityService(port);

        Map<String, BookActivitySummary> result = service.summarize(List.of(" a ", "b", "a", ""));

        assertThat(result.get("a")).isEqualTo(new BookActivitySummary("a", 2, 3, 4));
        assertThat(result.get("b")).isEqualTo(BookActivitySummary.empty("b"));
        verify(port).summarize(List.of("a", "b"));
    }
}
