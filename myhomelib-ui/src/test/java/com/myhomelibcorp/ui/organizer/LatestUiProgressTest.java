package com.myhomelibcorp.ui.organizer;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LatestUiProgressTest {
    @Test
    void floodOfUpdatesKeepsOneQueuedRunnableAndLatestValue() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<Integer> seen = new ArrayList<>();
        var dispatcher = new LatestUiProgress<Integer>(queue::addLast, seen::add);

        for (int index = 1; index <= 100_000; index++) dispatcher.offer(index);
        assertThat(queue).hasSize(1);
        queue.removeFirst().run();
        assertThat(seen).containsExactly(100_000);
        assertThat(queue).isEmpty();
    }

    @Test
    void updateDuringDispatchIsDeliveredInNextUiTurn() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<Integer> seen = new ArrayList<>();
        @SuppressWarnings("unchecked")
        LatestUiProgress<Integer>[] handle = new LatestUiProgress[1];
        handle[0] = new LatestUiProgress<>(queue::addLast, value -> {
            seen.add(value);
            if (value == 1) handle[0].offer(2);
        });
        handle[0].offer(1);
        queue.removeFirst().run();
        assertThat(queue).hasSize(1);
        queue.removeFirst().run();
        assertThat(seen).containsExactly(1, 2);
    }

    @Test
    void clearDropsOldProgressButAcceptsNewRun() {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<Integer> seen = new ArrayList<>();
        var dispatcher = new LatestUiProgress<Integer>(queue::addLast, seen::add);
        dispatcher.offer(1);
        dispatcher.clear();
        dispatcher.offer(3);
        assertThat(queue).hasSize(1);
        queue.removeFirst().run();
        assertThat(seen).containsExactly(3);
    }
}
