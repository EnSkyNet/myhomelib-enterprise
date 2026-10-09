package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.audio.AudioPosition;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutosaverConcurrencyTest {

    @Test
    void failedFlushFromPreviousBookCannotRedirtyAlreadySavedCurrentBook() throws Exception {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        CountDownLatch oldSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseOldSave = new CountDownLatch(1);
        ReaderPosition oldPosition = new ReaderPosition(0, 10, 1, 0);
        ReaderPosition newPosition = new ReaderPosition(1, 200, 4, 2);

        when(persistence.savePosition(eq("collection-A"), eq("old"), eq(oldPosition), anyLong())).thenAnswer(invocation -> {
            oldSaveEntered.countDown();
            assertThat(releaseOldSave.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        });
        when(persistence.savePosition(eq("collection-B"), eq("new"), eq(newPosition), anyLong())).thenReturn(true);

        ReaderPositionAutosaver autosaver = new ReaderPositionAutosaver(persistence);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            autosaver.start("collection-A", "old", 1000L);
            autosaver.mark(oldPosition);
            Future<Boolean> oldFlush = executor.submit(autosaver::flush);
            assertThat(oldSaveEntered.await(5, TimeUnit.SECONDS)).isTrue();

            autosaver.start("collection-B", "new", 2000L);
            autosaver.mark(newPosition);
            assertThat(autosaver.flush()).isTrue();

            releaseOldSave.countDown();
            assertThat(oldFlush.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(autosaver.flush()).isTrue();

            verify(persistence, times(1)).savePosition("collection-B", "new", newPosition, 2000L);
        } finally {
            releaseOldSave.countDown();
            autosaver.close();
            executor.shutdownNow();
        }
    }

    @Test
    void failedAudioFlushFromPreviousBookCannotRedirtyAlreadySavedCurrentBook() throws Exception {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        CountDownLatch oldSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseOldSave = new CountDownLatch(1);
        AudioPosition oldPosition = new AudioPosition(1000L, 0, 0);
        AudioPosition newPosition = new AudioPosition(5000L, 2, 1);

        when(persistence.saveAudioPosition(eq("collection-A"), eq("old"), eq(oldPosition), anyDouble())).thenAnswer(invocation -> {
            oldSaveEntered.countDown();
            assertThat(releaseOldSave.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        });
        when(persistence.saveAudioPosition(eq("collection-B"), eq("new"), eq(newPosition), anyDouble())).thenReturn(true);

        AudioPositionAutosaver autosaver = new AudioPositionAutosaver(persistence);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            autosaver.start("collection-A", "old");
            autosaver.mark(oldPosition, 10.0);
            Future<Boolean> oldFlush = executor.submit(autosaver::flush);
            assertThat(oldSaveEntered.await(5, TimeUnit.SECONDS)).isTrue();

            autosaver.start("collection-B", "new");
            autosaver.mark(newPosition, 75.0);
            assertThat(autosaver.flush()).isTrue();

            releaseOldSave.countDown();
            assertThat(oldFlush.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(autosaver.flush()).isTrue();

            verify(persistence, times(1)).saveAudioPosition("collection-B", "new", newPosition, 75.0);
        } finally {
            releaseOldSave.countDown();
            autosaver.close();
            executor.shutdownNow();
        }
    }
    @Test
    void failedReaderPositionIsRetriedAfterBookSwitchInsteadOfBeingLost() {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        ReaderPosition oldPosition = new ReaderPosition(0, 10, 1, 0);
        ReaderPosition newPosition = new ReaderPosition(1, 200, 4, 2);

        when(persistence.savePosition(eq("collection-A"), eq("old"), eq(oldPosition), anyLong()))
                .thenReturn(false, true);
        when(persistence.savePosition(eq("collection-B"), eq("new"), eq(newPosition), anyLong()))
                .thenReturn(true);

        ReaderPositionAutosaver autosaver = new ReaderPositionAutosaver(persistence);
        try {
            autosaver.start("collection-A", "old", 1000L);
            autosaver.mark(oldPosition);

            // start() tries to flush the old book. The first save fails, but the
            // snapshot must survive the state switch and be retried later.
            autosaver.start("collection-B", "new", 2000L);
            autosaver.mark(newPosition);
            assertThat(autosaver.flush()).isTrue();

            verify(persistence, times(2)).savePosition("collection-A", "old", oldPosition, 1000L);
            verify(persistence, times(1)).savePosition("collection-B", "new", newPosition, 2000L);
        } finally {
            autosaver.close();
        }
    }

    @Test
    void failedAudioPositionIsRetriedAfterBookSwitchInsteadOfBeingLost() {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        AudioPosition oldPosition = new AudioPosition(1000L, 0, 0);
        AudioPosition newPosition = new AudioPosition(5000L, 2, 1);

        when(persistence.saveAudioPosition(eq("collection-A"), eq("old"), eq(oldPosition), anyDouble()))
                .thenReturn(false, true);
        when(persistence.saveAudioPosition(eq("collection-B"), eq("new"), eq(newPosition), anyDouble()))
                .thenReturn(true);

        AudioPositionAutosaver autosaver = new AudioPositionAutosaver(persistence);
        try {
            autosaver.start("collection-A", "old");
            autosaver.mark(oldPosition, 10.0);

            autosaver.start("collection-B", "new");
            autosaver.mark(newPosition, 75.0);
            assertThat(autosaver.flush()).isTrue();

            verify(persistence, times(2)).saveAudioPosition("collection-A", "old", oldPosition, 10.0);
            verify(persistence, times(1)).saveAudioPosition("collection-B", "new", newPosition, 75.0);
        } finally {
            autosaver.close();
        }
    }

    @Test
    void olderReaderRetryCannotOverwriteNewerSaveWhenSameBookIsReopened() throws Exception {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        CountDownLatch oldSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseOldSave = new CountDownLatch(1);
        ReaderPosition oldPosition = new ReaderPosition(0, 10, 1, 0);
        ReaderPosition newPosition = new ReaderPosition(1, 900, 8, 3);

        AtomicInteger oldAttempts = new AtomicInteger();
        CopyOnWriteArrayList<String> saveOrder = new CopyOnWriteArrayList<>();
        when(persistence.savePosition(eq("collection-A"), eq("same"), eq(oldPosition), anyLong())).thenAnswer(invocation -> {
            int attempt = oldAttempts.incrementAndGet();
            saveOrder.add("old");
            if (attempt == 1) {
                oldSaveEntered.countDown();
                assertThat(releaseOldSave.await(5, TimeUnit.SECONDS)).isTrue();
                return false;
            }
            return true;
        });
        when(persistence.savePosition(eq("collection-A"), eq("same"), eq(newPosition), anyLong())).thenAnswer(invocation -> {
            saveOrder.add("new");
            return true;
        });

        ReaderPositionAutosaver autosaver = new ReaderPositionAutosaver(persistence);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            autosaver.start("collection-A", "same", 1000L);
            autosaver.mark(oldPosition);
            Future<Boolean> oldFlush = executor.submit(autosaver::flush);
            assertThat(oldSaveEntered.await(5, TimeUnit.SECONDS)).isTrue();

            autosaver.start("collection-A", "same", 2000L);
            autosaver.mark(newPosition);
            Future<Boolean> newFlush = executor.submit(autosaver::flush);

            releaseOldSave.countDown();
            assertThat(oldFlush.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(newFlush.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(autosaver.flush()).isTrue();

            int savesAfterSuccess = saveOrder.size();
            assertThat(saveOrder.getLast()).isEqualTo("new");
            assertThat(oldAttempts.get()).isBetween(1, 2);
            assertThat(autosaver.flush()).isTrue();
            assertThat(saveOrder).hasSize(savesAfterSuccess);
            verify(persistence, times(1)).savePosition("collection-A", "same", newPosition, 2000L);
        } finally {
            releaseOldSave.countDown();
            autosaver.close();
            executor.shutdownNow();
        }
    }

    @Test
    void olderAudioRetryCannotOverwriteNewerSaveWhenSameBookIsReopened() throws Exception {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        CountDownLatch oldSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseOldSave = new CountDownLatch(1);
        AudioPosition oldPosition = new AudioPosition(1000L, 0, 0);
        AudioPosition newPosition = new AudioPosition(9000L, 4, 1);

        AtomicInteger oldAttempts = new AtomicInteger();
        CopyOnWriteArrayList<String> saveOrder = new CopyOnWriteArrayList<>();
        when(persistence.saveAudioPosition(eq("collection-A"), eq("same"), eq(oldPosition), anyDouble())).thenAnswer(invocation -> {
            int attempt = oldAttempts.incrementAndGet();
            saveOrder.add("old");
            if (attempt == 1) {
                oldSaveEntered.countDown();
                assertThat(releaseOldSave.await(5, TimeUnit.SECONDS)).isTrue();
                return false;
            }
            return true;
        });
        when(persistence.saveAudioPosition(eq("collection-A"), eq("same"), eq(newPosition), anyDouble())).thenAnswer(invocation -> {
            saveOrder.add("new");
            return true;
        });

        AudioPositionAutosaver autosaver = new AudioPositionAutosaver(persistence);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            autosaver.start("collection-A", "same");
            autosaver.mark(oldPosition, 10.0);
            Future<Boolean> oldFlush = executor.submit(autosaver::flush);
            assertThat(oldSaveEntered.await(5, TimeUnit.SECONDS)).isTrue();

            autosaver.start("collection-A", "same");
            autosaver.mark(newPosition, 90.0);
            Future<Boolean> newFlush = executor.submit(autosaver::flush);

            releaseOldSave.countDown();
            assertThat(oldFlush.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(newFlush.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(autosaver.flush()).isTrue();

            int savesAfterSuccess = saveOrder.size();
            assertThat(saveOrder.getLast()).isEqualTo("new");
            assertThat(oldAttempts.get()).isBetween(1, 2);
            assertThat(autosaver.flush()).isTrue();
            assertThat(saveOrder).hasSize(savesAfterSuccess);
            verify(persistence, times(1)).saveAudioPosition("collection-A", "same", newPosition, 90.0);
        } finally {
            releaseOldSave.countDown();
            autosaver.close();
            executor.shutdownNow();
        }
    }

    @Test
    void readerCloseReportsUnsavedPositionWhenFinalPersistenceStillFails() {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        ReaderPosition position = new ReaderPosition(0, 42, 1, 0);
        when(persistence.savePosition(eq("collection-A"), eq("book"), eq(position), anyLong())).thenReturn(false);

        ReaderPositionAutosaver autosaver = new ReaderPositionAutosaver(persistence);
        autosaver.start("collection-A", "book", 1000L);
        autosaver.mark(position);

        assertThat(autosaver.closeAndFlush()).isFalse();
        verify(persistence, times(1)).savePosition("collection-A", "book", position, 1000L);
    }

    @Test
    void readerCloseRetriesRetainedPositionAndReportsSuccessWhenRetryPersists() {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        ReaderPosition position = new ReaderPosition(0, 42, 1, 0);
        when(persistence.savePosition(eq("collection-A"), eq("book"), eq(position), anyLong()))
                .thenReturn(false, true);

        ReaderPositionAutosaver autosaver = new ReaderPositionAutosaver(persistence);
        autosaver.start("collection-A", "book", 1000L);
        autosaver.mark(position);
        assertThat(autosaver.flush()).isFalse();

        assertThat(autosaver.closeAndFlush()).isTrue();
        verify(persistence, times(2)).savePosition("collection-A", "book", position, 1000L);
    }

    @Test
    void audioCloseReportsUnsavedPositionWhenFinalPersistenceStillFails() {
        NewReaderPersistenceService persistence = mock(NewReaderPersistenceService.class);
        AudioPosition position = new AudioPosition(2500L, 1, 0);
        when(persistence.saveAudioPosition(eq("collection-A"), eq("book"), eq(position), anyDouble())).thenReturn(false);

        AudioPositionAutosaver autosaver = new AudioPositionAutosaver(persistence);
        autosaver.start("collection-A", "book");
        autosaver.mark(position, 25.0);

        assertThat(autosaver.closeAndFlush()).isFalse();
        verify(persistence, times(1)).saveAudioPosition("collection-A", "book", position, 25.0);
    }

}
