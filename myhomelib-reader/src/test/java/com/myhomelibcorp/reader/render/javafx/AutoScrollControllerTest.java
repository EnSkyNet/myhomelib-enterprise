package com.myhomelibcorp.reader.render.javafx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AutoScrollControllerTest {
    @Test
    void reducedMotionPreventsAutoScrollFromStarting() {
        AtomicInteger pages = new AtomicInteger();
        AutoScrollController controller = new AutoScrollController(pages::incrementAndGet);

        controller.setMotionAllowed(false);
        controller.start();

        assertThat(controller.isMotionAllowed()).isFalse();
        assertThat(controller.isRunning()).isFalse();
        assertThat(pages).hasValue(0);
    }
}
