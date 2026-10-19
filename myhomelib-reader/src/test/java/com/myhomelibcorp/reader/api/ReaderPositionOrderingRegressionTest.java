package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import java.util.TreeSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReaderPositionOrderingRegressionTest {
    @Test
    void distinctPositionsAtSameOffsetDoNotCollapseInSortedCollections() {
        var positions = List.of(
                new ReaderPosition(0, 100, 1, 2),
                new ReaderPosition(1, 100, 1, 2),
                new ReaderPosition(0, 100, 1, 3),
                new ReaderPosition(0, 100, 2, 2));
        assertEquals(4, new TreeSet<>(positions).size());
        for (var left : positions) {
            for (var right : positions) {
                assertEquals(left.equals(right), left.compareTo(right) == 0);
                assertEquals(Integer.signum(left.compareTo(right)),
                        -Integer.signum(right.compareTo(left)));
            }
        }
    }

    @Test
    void offsetRemainsPrimarySortKeyWithoutSubtractionOverflow() {
        var early = new ReaderPosition(Integer.MAX_VALUE, 4, 10, 10);
        var later = new ReaderPosition(0, Long.MAX_VALUE, 0, 0);
        assertTrue(early.compareTo(later) < 0);
        assertTrue(later.compareTo(early) > 0);
    }
}
