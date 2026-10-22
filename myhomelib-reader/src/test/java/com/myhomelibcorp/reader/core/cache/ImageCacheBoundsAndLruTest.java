package com.myhomelibcorp.reader.core.cache;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ImageCacheBoundsAndLruTest {
    @Test
    void enforcesMinimumByteBudget() {
        assertThat(new ImageCache(1).getMaxSize()).isEqualTo(1024L * 1024L);
    }

    @Test
    void accountsForInsertedAndRemovedBytes() {
        ImageCache cache = new ImageCache(1024 * 1024);
        cache.put("a", new byte[300 * 1024]);
        cache.put("b", new byte[400 * 1024]);
        assertThat(cache.getCurrentSize()).isEqualTo(700L * 1024L);
        cache.evict("a");
        assertThat(cache.getCurrentSize()).isEqualTo(400L * 1024L);
    }

    @Test
    void leastRecentlyUsedImageIsEvictedToHonorByteLimit() {
        ImageCache cache = new ImageCache(1024 * 1024);
        cache.put("a", new byte[300 * 1024]);
        cache.put("b", new byte[400 * 1024]);
        cache.get("a");
        cache.put("c", new byte[500 * 1024]);
        assertThat(cache.contains("a")).isTrue();
        assertThat(cache.contains("b")).isFalse();
        assertThat(cache.contains("c")).isTrue();
    }

    @Test
    void containsDoesNotMoveAnEntryToMostRecentlyUsed() {
        ImageCache cache = new ImageCache(1024 * 1024);
        cache.put("a", new byte[400 * 1024]);
        cache.put("b", new byte[400 * 1024]);
        assertThat(cache.contains("a")).isTrue();
        cache.put("c", new byte[400 * 1024]);
        assertThat(cache.contains("a")).isFalse();
        assertThat(cache.contains("b")).isTrue();
    }

    @Test
    void replacingImageOnlyUsesNetCapacity() {
        ImageCache cache = new ImageCache(1024 * 1024);
        cache.put("a", new byte[400 * 1024]);
        cache.put("b", new byte[400 * 1024]);
        cache.put("a", new byte[500 * 1024]);
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.getCurrentSize()).isEqualTo(900L * 1024L);
    }

    @Test
    void oversizeReplacementPreservesPreviouslyCachedImage() {
        ImageCache cache = new ImageCache(1024 * 1024);
        byte[] original = new byte[100];
        cache.put("a", original);
        cache.put("a", new byte[1024 * 1024 + 1]);
        assertThat(cache.get("a")).isSameAs(original);
        assertThat(cache.getCurrentSize()).isEqualTo(100L);
    }

    @Test
    void rejectsEmptyAndNullInputsWithoutChangingAccounting() {
        ImageCache cache = new ImageCache(1024 * 1024);
        cache.put(null, new byte[5]);
        cache.put("bad", null);
        cache.put("empty", new byte[0]);
        cache.evict(null);
        assertThat(cache.size()).isZero();
        assertThat(cache.getCurrentSize()).isZero();
    }

    @Test
    void limitsTinyImageEntriesToOneThousand() {
        ImageCache cache = new ImageCache(Long.MAX_VALUE);
        for (int i = 0; i < 1001; i++) cache.put("image-" + i, new byte[1]);
        assertThat(cache.size()).isEqualTo(1000);
        assertThat(cache.getCurrentSize()).isEqualTo(1000L);
        assertThat(cache.contains("image-0")).isFalse();
        assertThat(cache.contains("image-1000")).isTrue();
    }

    @Test
    void countLimitEvictsOldestRatherThanRecentlyAccessed() {
        ImageCache cache = new ImageCache(Long.MAX_VALUE);
        for (int i = 0; i < 1000; i++) cache.put("image-" + i, new byte[1]);
        cache.get("image-0");
        cache.put("new", new byte[2]);
        assertThat(cache.contains("image-0")).isTrue();
        assertThat(cache.contains("image-1")).isFalse();
        assertThat(cache.getCurrentSize()).isEqualTo(1001L);
    }

    @Test
    void replacingAtCountLimitDoesNotEvictUnrelatedImages() {
        ImageCache cache = new ImageCache(Long.MAX_VALUE);
        for (int i = 0; i < 1000; i++) cache.put("image-" + i, new byte[1]);
        cache.put("image-400", new byte[2]);
        assertThat(cache.size()).isEqualTo(1000);
        assertThat(cache.contains("image-0")).isTrue();
        assertThat(cache.getCurrentSize()).isEqualTo(1001L);
    }

    @Test
    void clearResetsAllAccounting() {
        ImageCache cache = new ImageCache(1024 * 1024);
        cache.put("x", new byte[50]);
        cache.clear();
        assertThat(cache.size()).isZero();
        assertThat(cache.getCurrentSize()).isZero();
        assertThat(cache.get("x")).isNull();
    }

    @Test
    void statsHaveStableDecimalSeparator() {
        ImageCache cache = new ImageCache(1024 * 1024);
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(cache.getStats()).contains("0.00 MB", "1.00 MB");
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test
    void concurrentUpdatesRespectBothBounds() throws Exception {
        ImageCache cache = new ImageCache(1024 * 1024);
        int threads = 4;
        try (var pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch start = new CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < threads; worker++) {
                int id = worker;
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 350; i++) {
                            cache.put("k-" + id + "-" + i, new byte[1024]);
                            if (i % 3 == 0) cache.evict("k-" + id + "-" + (i / 3));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertThat(cache.getCurrentSize()).isBetween(0L, cache.getMaxSize());
        assertThat(cache.size()).isBetween(0, 1000);
    }
}
