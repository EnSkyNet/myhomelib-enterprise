package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.tts.TtsVoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TtsVoicePresentationTest {

    @Test
    void rendersHumanReadableVoiceNamesInsteadOfObjectIdentity() {
        TtsVoice voice = new TtsVoice("Microsoft Irina Desktop", "Microsoft Irina Desktop", "ru-RU");

        assertThat(NewReaderWorkspaceController.ttsVoiceLabel(voice))
                .isEqualTo("Microsoft Irina Desktop (ru-RU)")
                .doesNotContain("@")
                .doesNotContain("SystemTtsProvider$");
    }

    @Test
    void makesDuplicateVoiceLabelsUnambiguousWithoutExposingObjectIdentity() {
        List<String> labels = NewReaderWorkspaceController.ttsVoiceLabels(List.of(
                new TtsVoice("voice-a", "Microsoft Voice", "en-US"),
                new TtsVoice("voice-b", "Microsoft Voice", "en-US")
        ));

        assertThat(labels).containsExactly(
                "Microsoft Voice (en-US)",
                "Microsoft Voice (en-US) [2]"
        );
    }
}
