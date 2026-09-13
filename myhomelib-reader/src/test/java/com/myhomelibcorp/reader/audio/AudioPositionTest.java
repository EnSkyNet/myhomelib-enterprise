package com.myhomelibcorp.reader.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioPositionTest {
    @Test void roundTripsStableAudioAnchor() {
        AudioPosition position = new AudioPosition(123456L, 2, 7);
        assertThat(position.serialize()).isEqualTo("audio:123456:2:7");
        assertThat(AudioPosition.parse(position.serialize())).contains(position);
    }

    @Test void rejectsLegacyOrMalformedAnchorWithoutCorruptingIt() {
        assertThat(AudioPosition.parse("0:100:2:0")).isEmpty();
        assertThat(AudioPosition.parse("audio:oops:1:2")).isEmpty();
        assertThat(AudioPosition.parse(null)).isEmpty();
    }
}
