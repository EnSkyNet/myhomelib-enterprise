package com.myhomelibcorp.infrastructure.monitoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryMonitorTest {

    @Test
    void startStopStartWorksAndCloseIsIdempotent() {
        MemoryMonitor monitor = new MemoryMonitor();
        monitor.startMonitoring(10);
        assertThat(monitor.isRunning()).isTrue();
        monitor.stopMonitoring();
        assertThat(monitor.isRunning()).isFalse();

        monitor.startMonitoring(10);
        assertThat(monitor.isRunning()).isTrue();
        monitor.close();
        monitor.close();
        assertThat(monitor.isRunning()).isFalse();
    }

    @Test
    void invalidIntervalDoesNotChangeRunningState() {
        MemoryMonitor monitor = new MemoryMonitor();
        try {
            assertThatThrownBy(() -> monitor.startMonitoring(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("> 0");
            assertThat(monitor.isRunning()).isFalse();
        } finally {
            monitor.close();
        }
    }
}
