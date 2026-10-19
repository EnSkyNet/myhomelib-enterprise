package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.audio.AudioPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class ReaderRecoveryCrossProcessSafetyTest {
    @TempDir Path dir;

    @Test
    void staleTextAcknowledgementMustNotDeleteNewerPending() throws Exception {
        var first = new ReaderPositionRecoveryJournal(dir.resolve("text"));
        var other = new ReaderPositionRecoveryJournal(dir.resolve("text"));
        var stale = first.prepare("collection", "book", null, new ReaderPosition(0, 10, 0, 0), 100);
        var latest = other.prepare("collection", "book", null, new ReaderPosition(0, 20, 0, 0), 100);

        assertThat(first.acknowledgeIfMatches("collection", "book", stale)).isFalse();
        assertThat(other.load("collection", "book")).contains(latest);
        assertThat(other.acknowledgeIfMatches("collection", "book", latest)).isTrue();
        assertThat(first.load("collection", "book")).isEmpty();
    }

    @Test
    void staleAudioAcknowledgementMustNotDeleteNewerPending() throws Exception {
        var first = new ReaderAudioRecoveryJournal(dir.resolve("audio"));
        var other = new ReaderAudioRecoveryJournal(dir.resolve("audio"));
        var stale = first.prepare("collection", "book", null, new AudioPosition(10, 0, 0), 3);
        var latest = other.prepare("collection", "book", null, new AudioPosition(20, 0, 0), 5);

        assertThat(first.acknowledgeIfMatches("collection", "book", stale)).isFalse();
        assertThat(other.load("collection", "book")).contains(latest);
        assertThat(other.acknowledgeIfMatches("collection", "book", latest)).isTrue();
    }

    @Test
    void corruptedEvidenceMustNotBeDeletedByConditionalAcknowledgement() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(dir.resolve("text"));
        var observed = journal.prepare("collection", "book", null, new ReaderPosition(0, 10, 0, 0), 100);
        Files.writeString(journal.file("collection", "book"), "corrupt");
        assertThatThrownBy(() -> journal.acknowledgeIfMatches("collection", "book", observed))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(journal.file("collection", "book"))).isEqualTo("corrupt");
    }

    @Test
    void concurrentlyReplacingVerifiedRecordsMustKeepJournalParseable() throws Exception {
        var first = new ReaderPositionRecoveryJournal(dir.resolve("text"));
        var second = new ReaderPositionRecoveryJournal(dir.resolve("text"));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                int w = worker;
                tasks.add(pool.submit(() -> {
                    for (int i = 0; i < 40; i++) {
                        try {
                            first.prepare("collection", "book", null,
                                    new ReaderPosition(0, w * 40L + i, 0, 0), 1000);
                            if (second.load("collection", "book").isEmpty())
                                throw new AssertionError("Missing journal after concurrent publish");
                        } catch (IOException e) { throw new IllegalStateException(e); }
                    }
                }));
            }
            for (var task : tasks) task.get(60, TimeUnit.SECONDS);
        }
        assertThat(first.load("collection", "book")).isPresent();
    }
}
