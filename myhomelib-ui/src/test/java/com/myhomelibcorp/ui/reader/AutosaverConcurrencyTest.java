package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.audio.AudioPosition;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
}
