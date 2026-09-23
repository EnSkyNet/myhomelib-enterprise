package com.myhomelibcorp.application.tts;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TtsPlaybackService {
    private final List<TtsProvider> providers;
    private final ExecutorPort executor;
    private final Object startLock = new Object();
    private final AtomicReference<Playback> activePlayback = new AtomicReference<>();

    public TtsPlaybackService(List<TtsProvider> providers, ExecutorPort executor) {
        this.providers = providers == null ? List.of() : providers.stream().filter(Objects::nonNull).toList();
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public List<TtsVoice> availableVoices() {
        TtsProvider provider = selectProvider();
        if (provider == null) return List.of();
        try { return provider.availableVoices().stream().sorted(Comparator.comparing(TtsVoice::displayName)).toList(); }
        catch (RuntimeException failure) { return List.of(); }
    }

    public boolean isAvailable() { return selectProvider() != null && !availableVoices().isEmpty(); }

    public TtsPlayback start(TtsPlaybackRequest request, TtsPlaybackListener listener) {
        Objects.requireNonNull(request, "request");
        TtsProvider provider = selectProvider();
        if (provider == null) throw new IllegalStateException("System TTS is unavailable");
        List<TtsSentence> sentences = split(request);
        if (sentences.isEmpty()) throw new IllegalArgumentException("No readable text");

        TtsPlaybackListener effectiveListener = listener == null ? new TtsPlaybackListener() { } : listener;
        synchronized (startLock) {
            Playback playback = new Playback(provider, request, sentences, effectiveListener, () -> { });
            // Install the new owner before stopping the old one. The old playback's terminal
            // callback must therefore never clear the newly installed owner.
            Playback previous = activePlayback.getAndSet(playback);
            playback.setTerminalCallback(() -> activePlayback.compareAndSet(playback, null));
            if (previous != null) previous.stop();
            try {
                executor.execute(playback::run);
                return playback;
            } catch (RuntimeException schedulingFailure) {
                activePlayback.compareAndSet(playback, null);
                playback.stop();
                throw schedulingFailure;
            }
        }
    }

    private TtsProvider selectProvider() {
        return providers.stream().filter(TtsProvider::isAvailable).findFirst().orElse(null);
    }

    static List<TtsSentence> split(TtsPlaybackRequest request) {
        Locale locale = request.languageTag().isBlank() ? Locale.getDefault() : Locale.forLanguageTag(request.languageTag());
        BreakIterator iterator = BreakIterator.getSentenceInstance(locale);
        iterator.setText(request.text());
        List<TtsSentence> result = new ArrayList<>();
        int index = 0;
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String raw = request.text().substring(start, end);
            int left = 0, right = raw.length();
            while (left < right && Character.isWhitespace(raw.charAt(left))) left++;
            while (right > left && Character.isWhitespace(raw.charAt(right - 1))) right--;
            if (left >= right) continue;
            long globalStart = request.sourceStartOffset() + start + left;
            long globalEnd = request.sourceStartOffset() + start + right;
            result.add(new TtsSentence(index++, globalStart, globalEnd, raw.substring(left, right)));
        }
        return List.copyOf(result);
    }

    private static final class Playback implements TtsPlayback {
        private final Object lock = new Object();
        private final TtsProvider provider;
        private final TtsPlaybackRequest request;
        private final List<TtsSentence> sentences;
        private final TtsPlaybackListener listener;
        private volatile TtsPlaybackState state = TtsPlaybackState.PLAYING;
        private volatile Runnable terminalCallback = () -> { };
        private boolean repeatCurrentAfterPause;
        private int index;

        private Playback(TtsProvider provider, TtsPlaybackRequest request, List<TtsSentence> sentences,
                         TtsPlaybackListener listener, Runnable terminalCallback) {
            this.provider = provider;
            this.request = request;
            this.sentences = sentences;
            this.listener = listener;
            this.terminalCallback = terminalCallback == null ? () -> { } : terminalCallback;
        }

        private void setTerminalCallback(Runnable terminalCallback) {
            this.terminalCallback = terminalCallback == null ? () -> { } : terminalCallback;
        }

        @Override public TtsPlaybackState state() { return state; }

        @Override public void pause() {
            synchronized (lock) {
                if (state != TtsPlaybackState.PLAYING) return;
                state = TtsPlaybackState.PAUSED;
                repeatCurrentAfterPause = true;
                try { provider.cancelCurrent(); } catch (RuntimeException ignored) { }
                notifyState(TtsPlaybackState.PAUSED);
                lock.notifyAll();
            }
        }

        @Override public void resume() {
            synchronized (lock) {
                if (state != TtsPlaybackState.PAUSED) return;
                state = TtsPlaybackState.PLAYING;
                notifyState(TtsPlaybackState.PLAYING);
                lock.notifyAll();
            }
        }

        @Override public void stop() {
            boolean changed;
            synchronized (lock) {
                changed = !terminal(state);
                if (!changed) return;
                state = TtsPlaybackState.STOPPED;
                repeatCurrentAfterPause = false;
                try { provider.cancelCurrent(); } catch (RuntimeException ignored) { }
                lock.notifyAll();
            }
            notifyState(TtsPlaybackState.STOPPED);
            notifyTerminal();
        }

        private void run() {
            try {
                synchronized (lock) {
                    if (state == TtsPlaybackState.STOPPED) return;
                }
                notifyState(TtsPlaybackState.PLAYING);
                while (true) {
                    TtsSentence sentence;
                    synchronized (lock) {
                        while (state == TtsPlaybackState.PAUSED) {
                            try {
                                lock.wait();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                stop();
                                return;
                            }
                        }
                        if (state == TtsPlaybackState.STOPPED) return;
                        if (index >= sentences.size()) {
                            state = TtsPlaybackState.COMPLETED;
                            break;
                        }
                        sentence = sentences.get(index);
                    }

                    notifySentence(sentence);
                    try {
                        provider.speak(sentence.text(), request.voiceId(), request.rate());
                    } catch (Exception failure) {
                        synchronized (lock) {
                            if (state == TtsPlaybackState.STOPPED) return;
                            if (state == TtsPlaybackState.PAUSED || repeatCurrentAfterPause) {
                                repeatCurrentAfterPause = false;
                                continue;
                            }
                            state = TtsPlaybackState.FAILED;
                        }
                        notifyState(TtsPlaybackState.FAILED);
                        notifyFailure(failure);
                        return;
                    }

                    synchronized (lock) {
                        if (state == TtsPlaybackState.STOPPED) return;
                        if (state == TtsPlaybackState.PAUSED || repeatCurrentAfterPause) {
                            repeatCurrentAfterPause = false;
                            continue;
                        }
                        index++;
                        if (index >= sentences.size()) {
                            state = TtsPlaybackState.COMPLETED;
                            break;
                        }
                    }
                }
                notifyState(TtsPlaybackState.COMPLETED);
            } finally {
                if (terminal(state)) notifyTerminal();
            }
        }

        private void notifyState(TtsPlaybackState value) {
            try { listener.onStateChanged(value); } catch (RuntimeException ignored) { }
        }

        private void notifySentence(TtsSentence sentence) {
            try { listener.onSentenceStarted(sentence); } catch (RuntimeException ignored) { }
        }

        private void notifyFailure(Throwable failure) {
            try { listener.onFailed(failure); } catch (RuntimeException ignored) { }
        }

        private void notifyTerminal() {
            try { terminalCallback.run(); } catch (RuntimeException ignored) { }
        }

        private static boolean terminal(TtsPlaybackState state) {
            return state == TtsPlaybackState.STOPPED
                    || state == TtsPlaybackState.COMPLETED
                    || state == TtsPlaybackState.FAILED;
        }
    }

}
