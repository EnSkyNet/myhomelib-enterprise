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
