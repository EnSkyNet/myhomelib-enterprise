package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderPosition;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded previous-page history kept independently of the JavaFX shell. */
final class ReaderPageHistory {
    private final int capacity;
    private final Deque<ReaderPosition> entries = new ArrayDeque<>();

    ReaderPageHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    void push(ReaderPosition position) {
        if (position == null) return;
        if (entries.size() >= capacity) entries.pollFirst();
        entries.addLast(position);
    }

    ReaderPosition pollLast() {
        return entries.pollLast();
    }

    void clear() {
        entries.clear();
    }
}
