package com.myhomelibcorp.ui.reader;

import static com.myhomelibcorp.ui.reader.ReaderWorkspaceSupport.rootMessage;

import com.myhomelibcorp.application.tts.TtsPlayback;
import com.myhomelibcorp.application.tts.TtsPlaybackListener;
import com.myhomelibcorp.application.tts.TtsPlaybackRequest;
import com.myhomelibcorp.application.tts.TtsPlaybackService;
import com.myhomelibcorp.application.tts.TtsPlaybackState;
import com.myhomelibcorp.application.tts.TtsSentence;
import com.myhomelibcorp.application.tts.TtsVoice;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextInputDialog;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Owns text-to-speech playback state and dialogs for one Reader workspace. */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@RequiredArgsConstructor
public class ReaderTtsCoordinator {
    private final TtsPlaybackService playbackService;
    private final DialogService dialogService;
    private final LocalizationService i18n;

    private volatile TtsPlayback playback;
    private long generation;
    private String selectedVoiceId;
    private double selectedRate = 1.0;
    private Context activeContext;

    public void start(Context context) {
        if (!usable(context)) return;
        ReaderDocument document = context.document();
        ReaderPosition position = context.position();

        List<TtsVoice> voices = playbackService.availableVoices();
        if (voices.isEmpty()) {
            dialogService.showInfo(i18n.text("ui.reader.tts.title"), i18n.text("ui.reader.tts.unavailable"));
            return;
        }
        String language = document.metadata().language();
        TtsVoice suggested = voices.stream()
                .filter(v -> selectedVoiceId != null && selectedVoiceId.equals(v.id()))
                .findFirst()
                .orElseGet(() -> voices.stream()
                        .filter(v -> language != null && !language.isBlank()
                                && v.languageTag().toLowerCase(Locale.ROOT).startsWith(language.toLowerCase(Locale.ROOT)))
                        .findFirst().orElse(voices.getFirst()));
        List<String> voiceLabels = voiceLabels(voices);
        int suggestedIndex = Math.max(0, voices.indexOf(suggested));
        ChoiceDialog<String> voiceDialog = new ChoiceDialog<>(voiceLabels.get(suggestedIndex), voiceLabels);
        DialogLocalization.prepare(voiceDialog);
        voiceDialog.setTitle(i18n.text("ui.reader.tts.title"));
        voiceDialog.setHeaderText(i18n.text("ui.reader.tts.voice_header"));
        voiceDialog.setContentText(i18n.text("ui.reader.tts.voice_label"));
        Optional<String> selectedVoiceLabel = voiceDialog.showAndWait();
        if (selectedVoiceLabel.isEmpty() || !context.current()) return;
        int selectedVoiceIndex = voiceLabels.indexOf(selectedVoiceLabel.get());
        if (selectedVoiceIndex < 0 || selectedVoiceIndex >= voices.size()) return;
        TtsVoice selectedVoice = voices.get(selectedVoiceIndex);

        TextInputDialog rateDialog = new TextInputDialog(String.format(Locale.ROOT, "%.2f", selectedRate));
        DialogLocalization.prepare(rateDialog);
        rateDialog.setTitle(i18n.text("ui.reader.tts.title"));
        rateDialog.setHeaderText(i18n.text("ui.reader.tts.speed_header"));
        rateDialog.setContentText(i18n.text("ui.reader.tts.speed_label"));
        Optional<String> selectedRateText = rateDialog.showAndWait();
        if (selectedRateText.isEmpty() || !context.current()) return;
        double rate;
        try {
            rate = Double.parseDouble(selectedRateText.get().trim().replace(',', '.'));
        } catch (NumberFormatException invalid) {
            showInvalidRate();
            return;
        }
        if (!Double.isFinite(rate) || rate < 0.5 || rate > 2.0) {
            showInvalidRate();
            return;
        }

        long total = document.totalTextLength();
        int start = (int) Math.max(0L, Math.min((long) Integer.MAX_VALUE, Math.min(position.textOffset(), total)));
        int end = (int) Math.max(start, Math.min((long) Integer.MAX_VALUE, total));
        String readableText = document.text().getText(start, end);
        if (readableText.isBlank() || !context.current()) return;

        stop();
        long token = ++generation;
        selectedVoiceId = selectedVoice.id();
        selectedRate = rate;
        activeContext = context;
        TtsPlaybackRequest request = new TtsPlaybackRequest(readableText, start, language, selectedVoiceId, rate);
        try {
            playback = playbackService.start(request, new TtsPlaybackListener() {
                @Override public void onStateChanged(TtsPlaybackState state) {
                    UiExecutor.runOnUiThread(() -> applyState(token, state));
                }

                @Override public void onSentenceStarted(TtsSentence sentence) {
                    UiExecutor.runOnUiThread(() -> showSentence(token, sentence));
                }

                @Override public void onFailed(Throwable error) {
                    UiExecutor.runOnUiThread(() -> {
                        if (token == generation && current(activeContext)) {
                            dialogService.showError(i18n.text("ui.reader.tts.title"),
                                    i18n.format("ui.reader.tts.failed", rootMessage(error)));
                        }
                    });
                }
            });
            context.updateState(true, false);
        } catch (RuntimeException error) {
            playback = null;
            activeContext = null;
            context.updateState(false, false);
            dialogService.showError(i18n.text("ui.reader.tts.title"),
                    i18n.format("ui.reader.tts.failed", rootMessage(error)));
        }
    }

    public void pauseResume() {
        TtsPlayback current = playback;
        if (current == null) return;
        if (current.state() == TtsPlaybackState.PAUSED) current.resume();
        else if (current.state() == TtsPlaybackState.PLAYING) current.pause();
    }

    public void stop() {
        generation++;
        TtsPlayback current = playback;
        Context context = activeContext;
        playback = null;
        activeContext = null;
        if (current != null) current.stop();
        if (context != null) {
            context.clearHighlight();
            context.updateState(false, false);
        }
    }

    private void applyState(long token, TtsPlaybackState state) {
        Context context = activeContext;
        if (token != generation || !current(context)) return;
        boolean active = state == TtsPlaybackState.PLAYING || state == TtsPlaybackState.PAUSED;
        context.updateState(active, state == TtsPlaybackState.PAUSED);
        if (!active) {
            context.clearHighlight();
            playback = null;
            activeContext = null;
        }
    }

    private void showSentence(long token, TtsSentence sentence) {
        Context context = activeContext;
        if (token != generation || !current(context) || sentence == null) return;
        ReaderDocument document = context.document();
        long offset = Math.max(0L, Math.min(sentence.startOffset(), document.totalTextLength()));
        ReaderPosition target = new ReaderPosition(Math.max(0, document.chapterIndexAt(offset)), offset, 0, 0);
        context.showSentence(target, sentence);
    }

    private void showInvalidRate() {
        dialogService.showError(i18n.text("common.error"), i18n.text("ui.reader.tts.speed_invalid"));
    }

    static String voiceLabel(TtsVoice voice) {
        if (voice == null) return "";
        String name = voice.displayName() == null || voice.displayName().isBlank() ? voice.id() : voice.displayName();
        String language = voice.languageTag() == null ? "" : voice.languageTag().trim();
        return language.isBlank() ? name : name + " (" + language + ")";
    }

    static List<String> voiceLabels(List<TtsVoice> voices) {
        List<String> labels = new ArrayList<>();
        if (voices == null) return labels;
        for (TtsVoice voice : voices) {
            String base = voiceLabel(voice);
            String label = base;
            int suffix = 2;
            while (labels.contains(label)) label = base + " [" + suffix++ + "]";
            labels.add(label);
        }
        return List.copyOf(labels);
    }

    private static boolean usable(Context context) {
        return context != null && context.document() != null && context.position() != null && context.current();
    }

    private static boolean current(Context context) {
        return context != null && context.current();
    }

    @FunctionalInterface
    public interface StatePresenter {
        void update(boolean active, boolean paused);
    }

    @FunctionalInterface
    public interface SentencePresenter {
        void show(ReaderPosition position, TtsSentence sentence);
    }

    public record Context(
            ReaderDocument document,
            ReaderPosition position,
            BooleanSupplier stillCurrent,
            StatePresenter statePresenter,
            Runnable clearHighlightAction,
            SentencePresenter sentencePresenter) {

        public boolean current() {
            return stillCurrent != null && stillCurrent.getAsBoolean();
        }

        void updateState(boolean active, boolean paused) {
            if (statePresenter != null) statePresenter.update(active, paused);
        }

        void clearHighlight() {
            if (clearHighlightAction != null) clearHighlightAction.run();
        }

        void showSentence(ReaderPosition target, TtsSentence sentence) {
            if (sentencePresenter != null) sentencePresenter.show(target, sentence);
        }
    }
}
