package com.myhomelibcorp.infrastructure.profiling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
public class PerformanceProfilerTest {

    @Test
    void testProfiler() {
        PerformanceProfiler profiler = new PerformanceProfiler();
        profiler.start("test");
        profiler.stop("test");
        profiler.logSummary();
        assertThat(profiler).isNotNull();
    }
}