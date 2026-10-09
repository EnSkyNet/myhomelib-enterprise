package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TocEntryTest {
    @Test
    void addChildRetainsChildWhenOriginalChildrenListIsNull() {
        TocEntry parent = new TocEntry("Parent", 0, 0, null);
        TocEntry child = new TocEntry("Child", 10, 1);

        TocEntry updated = parent.addChild(child);

        assertThat(updated).isSameAs(parent);
        assertThat(updated.children()).containsExactly(child);
        assertThat(updated.hasChildren()).isTrue();
    }

    @Test
    void addChildMutatesExistingBuilderListForCompatibility() {
        TocEntry parent = new TocEntry("Parent", 0, 0);
        TocEntry child = new TocEntry("Child", 10, 1);

        TocEntry returned = parent.addChild(child);

        assertThat(returned).isSameAs(parent);
        assertThat(parent.children()).containsExactly(child);
    }

    @Test
    void addChildWorksWhenConstructorReceivesImmutableList() {
        TocEntry first = new TocEntry("First", 10, 1);
        TocEntry second = new TocEntry("Second", 20, 1);
        TocEntry parent = new TocEntry("Parent", 0, 0, List.of(first));

        parent.addChild(second);

        assertThat(parent.children()).containsExactly(first, second);
    }

    @Test
    void constructorDetachesChildrenFromCallerList() {
        java.util.ArrayList<TocEntry> source = new java.util.ArrayList<>();
        TocEntry parent = new TocEntry("Parent", 0, 0, source);
        source.add(new TocEntry("External", 10, 1));

        assertThat(parent.children()).isEmpty();
    }

    @Test
    void addChildIgnoresNullInsteadOfPollutingTree() {
        TocEntry parent = new TocEntry("Parent", 0, 0);

        parent.addChild(null);

        assertThat(parent.children()).isEmpty();
    }

    @Test
    void containsOffsetDoesNotOverflowNearLongMaxValue() {
        TocEntry entry = new TocEntry("End", Long.MAX_VALUE - 50, 0);

        assertThat(entry.containsOffset(Long.MAX_VALUE - 1)).isTrue();
        assertThat(entry.containsOffset(Long.MAX_VALUE - 51)).isFalse();
    }
}
