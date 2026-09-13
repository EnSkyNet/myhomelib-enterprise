package com.myhomelibcorp.application.tts;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class TtsPlaybackService {
    private final List<TtsProvider> providers;
    private final ExecutorPort executor;

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
        Playback playback = new Playback(provider, request, sentences, listener == null ? new TtsPlaybackListener() { } : listener);
        executor.execute(playback::run);
        return playback;
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
        private boolean repeatCurrentAfterPause;
        private int index;

        private Playback(TtsProvider provider, TtsPlaybackRequest request, List<TtsSentence> sentences, TtsPlaybackListener listener) {
            this.provider = provider; this.request = request; this.sentences = sentences; this.listener = listener;
        }
        @Override public TtsPlaybackState state() { return state; }
        @Override public void pause() {
            synchronized (lock) {
                if (state != TtsPlaybackState.PLAYING) return;
                state = TtsPlaybackState.PAUSED;
                repeatCurrentAfterPause = true;
                provider.cancelCurrent();
                listener.onStateChanged(state);
                lock.notifyAll();
            }
        }
        @Override public void resume() {
            synchronized (lock) {
                if (state != TtsPlaybackState.PAUSED) return;
                state = TtsPlaybackState.PLAYING; listener.onStateChanged(state); lock.notifyAll();
            }
        }
        @Override public void stop() {
            synchronized (lock) {
                if (terminal(state)) return;
                state = TtsPlaybackState.STOPPED; provider.cancelCurrent(); listener.onStateChanged(state); lock.notifyAll();
            }
        }
        private void run() {
            listener.onStateChanged(TtsPlaybackState.PLAYING);
            while (index < sentences.size()) {
                synchronized (lock) {
                    while (state == TtsPlaybackState.PAUSED) {
                        try { lock.wait(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); stop(); return; }
                    }
                    if (state == TtsPlaybackState.STOPPED) return;
                }
                TtsSentence sentence = sentences.get(index);
                listener.onSentenceStarted(sentence);
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
                    listener.onStateChanged(TtsPlaybackState.FAILED);
                    listener.onFailed(failure);
                    return;
                }
                synchronized (lock) {
                    if (state == TtsPlaybackState.STOPPED) return;
                    if (state == TtsPlaybackState.PAUSED || repeatCurrentAfterPause) {
                        repeatCurrentAfterPause = false;
                        continue; // repeat interrupted sentence after resume, even if resume raced ahead
                    }
                    index++;
                }
            }
            state = TtsPlaybackState.COMPLETED;
            listener.onStateChanged(state);
        }
        private static boolean terminal(TtsPlaybackState state) {
            return state == TtsPlaybackState.STOPPED || state == TtsPlaybackState.COMPLETED || state == TtsPlaybackState.FAILED;
        }
    }
}
