package com.myhomelibcorp.startup;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class StartupExceptionCauseCycleTest {
    @Test
    void twoNodeCauseCycleIsReportedWithoutHanging() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second");
        first.initCause(second);
        second.initCause(first);
        StartupException result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new StartupException("recovery", first));
        assertEquals("recovery", result.taskId());
        assertEquals("Required startup task failed: recovery: second", result.getMessage());
        assertSame(first, result.getCause());
    }

    @Test
    void threeNodeCycleDoesNotHang() {
        Exception first = new Exception("one");
        Exception second = new Exception("two");
        Exception third = new Exception("three");
        first.initCause(second);
        second.initCause(third);
        third.initCause(first);
        StartupException result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new StartupException("migration", first));
        assertEquals("Required startup task failed: migration: three", result.getMessage());
    }

    @Test
    void ordinaryNestedFailureRetainsDeepestMessage() {
        StartupException exception = new StartupException("scan",
                new IllegalStateException("outer", new IllegalArgumentException("inner")));
        assertEquals("Required startup task failed: scan: inner", exception.getMessage());
    }

    @Test
    void blankNestedFailureUsesClassName() {
        StartupException exception = new StartupException("scan",
                new IllegalStateException("outer", new IllegalArgumentException("  ")));
        assertEquals("Required startup task failed: scan: IllegalArgumentException", exception.getMessage());
    }

    @Test
    void absentCauseUsesStableFallback() {
        assertEquals("Required startup task failed: scan: Unknown error",
                new StartupException("scan", null).getMessage());
    }
}
