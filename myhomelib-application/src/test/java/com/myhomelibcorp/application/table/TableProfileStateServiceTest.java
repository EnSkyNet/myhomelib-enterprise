package com.myhomelibcorp.application.table;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TableProfileStateServiceTest {
    @Test
    void nonFinitePersistedWidthFallsBackToDefault() {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.get(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.endsWith(".width") ? "NaN" : invocation.getArgument(1);
        });
        TableProfileStateService service = new TableProfileStateService(settings);

        TableProfileState state = service.load("books", List.of(new TableColumnProfile("title", 240.0, true, 0)));

        assertThat(state.columns()).singleElement().extracting(TableColumnProfile::width).isEqualTo(240.0);
    }
}
