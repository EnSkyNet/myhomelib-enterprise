package com.myhomelibcorp.application.tts;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TtsPlaybackServiceTest {
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void splitsUnicodeSentencesAndPreservesSourceOffsets() {
        TtsPlaybackRequest request = new TtsPlaybackRequest("  Привіт світе!  Це тест. 日本語です。 ", 100, "uk-UA", "voice", 1.0);

        List<TtsSentence> sentences = TtsPlaybackService.split(request);

        assertThat(sentences).extracting(TtsSentence::text)
                .containsExactly("Привіт світе!", "Це тест.", "日本語です。");
        assertThat(sentences.getFirst().startOffset()).isEqualTo(102);
        assertThat(sentences.getFirst().endOffset()).isEqualTo(115);
        assertThat(sentences.get(1).startOffset()).isGreaterThan(sentences.getFirst().endOffset());
    }

    @Test
    void exposesSortedSystemVoicesAndRunsSpeechOffCallerThread() {
        BlockingProvider provider = new BlockingProvider();
        TtsPlaybackService service = new TtsPlaybackService(List.of(provider), new AsyncExecutor(pool));
        assertThat(service.availableVoices()).extracting(TtsVoice::displayName)
                .containsExactly("Alpha", "Zulu");

        long started = System.nanoTime();
        TtsPlayback playback = service.start(
                new TtsPlaybackRequest("First sentence. Second sentence.", 0, "en", "alpha", 1.25),
                new TtsPlaybackListener() { });
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).isLessThan(250);
        await().atMost(Duration.ofSeconds(2)).until(() -> provider.speakCalls.get() == 1);
        assertThat(playback.state()).isEqualTo(TtsPlaybackState.PLAYING);

        playback.pause();
        assertThat(playback.state()).isEqualTo(TtsPlaybackState.PAUSED);
        assertThat(provider.cancelCalls).hasValue(1);

        playback.resume();
        await().atMost(Duration.ofSeconds(2)).until(() -> playback.state() == TtsPlaybackState.COMPLETED);
        assertThat(provider.speakCalls.get()).isGreaterThanOrEqualTo(3); // interrupted sentence is repeated, then next one
    }

    @Test
    void stopCancelsCurrentNativeUtteranceWithoutBlocking() {
        BlockingProvider provider = new BlockingProvider();
        TtsPlaybackService service = new TtsPlaybackService(List.of(provider), new AsyncExecutor(pool));
        TtsPlayback playback = service.start(
                new TtsPlaybackRequest("Long sentence for cancellation.", 0, "en", "alpha", 1.0),
                new TtsPlaybackListener() { });
        await().atMost(Duration.ofSeconds(2)).until(() -> provider.speakCalls.get() == 1);

        playback.stop();

        assertThat(playback.state()).isEqualTo(TtsPlaybackState.STOPPED);
        assertThat(provider.cancelCalls).hasValue(1);
    }


    @Test
    void startingNewPlaybackStopsPreviousOwnerBeforeSecondRun() {
        BlockingProvider provider = new BlockingProvider();
        TtsPlaybackService service = new TtsPlaybackService(List.of(provider), new AsyncExecutor(pool));
        TtsPlayback first = service.start(
                new TtsPlaybackRequest("First playback owns the provider.", 0, "en", "alpha", 1.0),
                new TtsPlaybackListener() { });
        await().atMost(Duration.ofSeconds(2)).until(() -> provider.speakCalls.get() == 1);

        TtsPlayback second = service.start(
                new TtsPlaybackRequest("Second playback replaces it.", 0, "en", "alpha", 1.0),
                new TtsPlaybackListener() { });

        assertThat(first.state()).isEqualTo(TtsPlaybackState.STOPPED);
        assertThat(provider.cancelCalls).hasValue(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> second.state() == TtsPlaybackState.COMPLETED);
        assertThat(provider.speakCalls).hasValue(2);
    }

    @Test
    void executorRejectionClearsActiveOwnerSoNextStartCanProceed() {
        ImmediateProvider provider = new ImmediateProvider();
        RejectOnceExecutor rejecting = new RejectOnceExecutor(pool);
        TtsPlaybackService service = new TtsPlaybackService(List.of(provider), rejecting);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.start(
                        new TtsPlaybackRequest("Rejected once.", 0, "en", "alpha", 1.0),
                        new TtsPlaybackListener() { }))
                .isInstanceOf(RejectedExecutionException.class);

        TtsPlayback second = service.start(
                new TtsPlaybackRequest("Accepted after rejection.", 0, "en", "alpha", 1.0),
                new TtsPlaybackListener() { });
        await().atMost(Duration.ofSeconds(2)).until(() -> second.state() == TtsPlaybackState.COMPLETED);
        assertThat(provider.speakCalls).hasValue(1);
    }

    @Test
    void brokenAvailabilityProbeDoesNotHideHealthyFallbackProvider() {
        TtsProvider broken = new TtsProvider() {
            @Override public String id() { return "broken-probe"; }
            @Override public String displayName() { return "Broken"; }
            @Override public boolean isAvailable() { throw new IllegalStateException("probe failed"); }
            @Override public List<TtsVoice> availableVoices() { throw new AssertionError("must not be called"); }
            @Override public void speak(String text, String voiceId, double rate) { }
            @Override public void cancelCurrent() { }
        };
        ImmediateProvider healthy = new ImmediateProvider();
        TtsPlaybackService service = new TtsPlaybackService(List.of(broken, healthy), new AsyncExecutor(pool));

        assertThat(service.availableVoices()).extracting(TtsVoice::displayName).containsExactly("Alpha");
        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void brokenVoiceEnumerationDoesNotHideHealthyFallbackProvider() {
        TtsProvider broken = new TtsProvider() {
            @Override public String id() { return "broken-voices"; }
            @Override public String displayName() { return "Broken"; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<TtsVoice> availableVoices() { throw new IllegalStateException("voices failed"); }
            @Override public void speak(String text, String voiceId, double rate) { }
            @Override public void cancelCurrent() { }
        };
        ImmediateProvider healthy = new ImmediateProvider();
        TtsPlaybackService service = new TtsPlaybackService(List.of(broken, healthy), new AsyncExecutor(pool));

        assertThat(service.availableVoices()).extracting(TtsVoice::displayName).containsExactly("Alpha");
    }

    @Test
    void startUsesTheSameFallbackProviderWhoseVoicesWereExposed() {
        AtomicInteger firstSpeakCalls = new AtomicInteger();
        TtsProvider availableButVoiceless = new TtsProvider() {
            @Override public String id() { return "voiceless"; }
            @Override public String displayName() { return "Voiceless"; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<TtsVoice> availableVoices() { return List.of(); }
            @Override public void speak(String text, String voiceId, double rate) { firstSpeakCalls.incrementAndGet(); }
            @Override public void cancelCurrent() { }
        };
        ImmediateProvider healthy = new ImmediateProvider();
        TtsPlaybackService service = new TtsPlaybackService(List.of(availableButVoiceless, healthy), new AsyncExecutor(pool));

        assertThat(service.availableVoices()).extracting(TtsVoice::id).containsExactly("alpha");
        TtsPlayback playback = service.start(
                new TtsPlaybackRequest("Fallback provider must speak.", 0, "en", "alpha", 1.0),
                new TtsPlaybackListener() { });

        await().atMost(Duration.ofSeconds(2)).until(() -> playback.state() == TtsPlaybackState.COMPLETED);
        assertThat(firstSpeakCalls).hasValue(0);
        assertThat(healthy.speakCalls).hasValue(1);
    }

    private static final class AsyncExecutor implements ExecutorPort {
        private final ExecutorService pool;
        private AsyncExecutor(ExecutorService pool) { this.pool = pool; }
        @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
            return CompletableFuture.supplyAsync(() -> {
                try { return task.call(); }
                catch (Exception e) { throw new CompletionException(e); }
            }, pool);
        }
        @Override public void execute(Runnable task) { pool.execute(task); }
    }


    private static final class RejectOnceExecutor implements ExecutorPort {
        private final ExecutorService pool;
        private final AtomicInteger calls = new AtomicInteger();

        private RejectOnceExecutor(ExecutorService pool) { this.pool = pool; }

        @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override public void execute(Runnable task) {
            if (calls.getAndIncrement() == 0) throw new RejectedExecutionException("test rejection");
            pool.execute(task);
        }
    }

    private static final class ImmediateProvider implements TtsProvider {
        private final AtomicInteger speakCalls = new AtomicInteger();

        @Override public String id() { return "immediate"; }
        @Override public String displayName() { return "Immediate"; }
        @Override public boolean isAvailable() { return true; }
        @Override public List<TtsVoice> availableVoices() {
            return List.of(new TtsVoice("alpha", "Alpha", "en"));
        }
        @Override public void speak(String text, String voiceId, double rate) { speakCalls.incrementAndGet(); }
        @Override public void cancelCurrent() { }
    }

    private static final class BlockingProvider implements TtsProvider {
        private final AtomicInteger speakCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private volatile CountDownLatch currentRelease = new CountDownLatch(1);

        @Override public String id() { return "test"; }
        @Override public String displayName() { return "Test"; }
        @Override public boolean isAvailable() { return true; }
        @Override public List<TtsVoice> availableVoices() {
            return List.of(new TtsVoice("zulu", "Zulu", "en"), new TtsVoice("alpha", "Alpha", "en"));
        }
        @Override public void speak(String text, String voiceId, double rate) throws Exception {
            speakCalls.incrementAndGet();
            CountDownLatch latch = currentRelease;
            if (!latch.await(5, TimeUnit.SECONDS)) throw new TimeoutException("test utterance was not released");
            currentRelease = new CountDownLatch(0); // resumed/remainder utterances complete immediately
        }
        @Override public void cancelCurrent() {
            cancelCalls.incrementAndGet();
            currentRelease.countDown();
        }
    }
}
