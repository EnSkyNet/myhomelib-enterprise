package com.myhomelibcorp.reader.core.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageCacheReplacementTest {

    @Test
    void replacingExistingKeyDoesNotEvictUnrelatedEntryWhenNetSizeStillFitsBudget() {
        ImageCache cache = new ImageCache(1024 * 1024);
        byte[] first = new byte[400 * 1024];
        byte[] second = new byte[400 * 1024];
        byte[] replacement = new byte[500 * 1024];

        cache.put("first", first);
        cache.put("second", second);
        cache.put("first", replacement);

        assertThat(cache.contains("first")).isTrue();
        assertThat(cache.contains("second")).isTrue();
        assertThat(cache.getCurrentSize()).isEqualTo(900L * 1024L);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void replacingExistingKeyEvictsOnlyWhatIsNeededForFinalBudget() {
        ImageCache cache = new ImageCache(1024 * 1024);
        cache.put("first", new byte[300 * 1024]);
        cache.put("second", new byte[400 * 1024]);
        cache.put("third", new byte[200 * 1024]);

        // Touch second/third so first is the LRU candidate after its replacement is removed.
        cache.get("second");
        cache.get("third");
        cache.put("second", new byte[700 * 1024]);

        assertThat(cache.contains("second")).isTrue();
        assertThat(cache.getCurrentSize()).isLessThanOrEqualTo(cache.getMaxSize());
    }
}
