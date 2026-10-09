package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.opds.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OPDSStartupTaskTest {
    @Test
    void disabledAutostartDoesNotInstantiateServer() {
        OpdsSettingsService settings = mock(OpdsSettingsService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpdsServerControl> serverProvider = mock(ObjectProvider.class);
        when(settings.load()).thenReturn(OpdsServerSettings.defaults());
        OPDSStartupTask task = new OPDSStartupTask(settings, serverProvider);

        StartupTaskResult result = task.execute(new StartupContext(StartupTestFixtures.collection("c1")));

        assertThat(result.executed()).isFalse();
        verifyNoInteractions(serverProvider);
    }

    @Test
    void enabledAutostartResolvesAndStartsServerAndFailedStatusIsSurfacedToPolicyLayer() {
        OpdsSettingsService settings = mock(OpdsSettingsService.class);
        OpdsServerControl server = mock(OpdsServerControl.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpdsServerControl> serverProvider = mock(ObjectProvider.class);
        OpdsServerSettings enabled = new OpdsServerSettings("127.0.0.1", 8088, false, "", "", true);
        when(settings.load()).thenReturn(enabled);
        when(serverProvider.getObject()).thenReturn(server);
        when(server.start(enabled)).thenReturn(new OpdsServerStatus(false, "127.0.0.1", 8088, "", false, "ui.opds.status.start_failed", "port busy"));
        OPDSStartupTask task = new OPDSStartupTask(settings, serverProvider);

        assertThatThrownBy(() -> task.execute(new StartupContext(StartupTestFixtures.collection("c1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port busy");
        assertThat(task.failurePolicy()).isEqualTo(StartupFailurePolicy.BEST_EFFORT);
        verify(serverProvider).getObject();
        verify(server).start(enabled);
    }
}
