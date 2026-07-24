package com.myhomelibcorp.infrastructure.profiling;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class PerformanceProfilerTest {

    @Autowired
    private PerformanceProfiler profiler;

    @Test
    void testProfiler() {
        profiler.start("test");
        profiler.stop("test");
        profiler.logSummary();
        assertThat(profiler).isNotNull();
    }
}