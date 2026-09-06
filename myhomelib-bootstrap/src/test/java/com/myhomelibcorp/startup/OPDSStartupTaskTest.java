package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.opds.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OPDSStartupTaskTest {
    @Test
    void disabledAutostartDoesNotTouchServer() {
        OpdsSettingsService settings = mock(OpdsSettingsService.class);
        OpdsServerControl server = mock(OpdsServerControl.class);
        when(settings.load()).thenReturn(OpdsServerSettings.defaults());
        OPDSStartupTask task = new OPDSStartupTask(settings, server);

        StartupTaskResult result = task.execute(new StartupContext(StartupTestFixtures.collection("c1")));

        assertThat(result.executed()).isFalse();
        verifyNoInteractions(server);
    }

    @Test
    void enabledAutostartStartsServerAndFailedStatusIsSurfacedToPolicyLayer() {
        OpdsSettingsService settings = mock(OpdsSettingsService.class);
        OpdsServerControl server = mock(OpdsServerControl.class);
        OpdsServerSettings enabled = new OpdsServerSettings("127.0.0.1", 8088, false, "", "", true);
        when(settings.load()).thenReturn(enabled);
        when(server.start(enabled)).thenReturn(new OpdsServerStatus(false, "127.0.0.1", 8088, "", false, "port busy"));
        OPDSStartupTask task = new OPDSStartupTask(settings, server);

        assertThatThrownBy(() -> task.execute(new StartupContext(StartupTestFixtures.collection("c1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port busy");
        assertThat(task.failurePolicy()).isEqualTo(StartupFailurePolicy.BEST_EFFORT);
    }
}
