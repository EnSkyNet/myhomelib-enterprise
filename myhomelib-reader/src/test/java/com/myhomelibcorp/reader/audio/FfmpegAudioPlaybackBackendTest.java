package com.myhomelibcorp.reader.audio;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegAudioPlaybackBackendTest {
    @Test void parsesDurationAndEmbeddedChapters() throws Exception {
        String output = """
                [CHAPTER]
                start_time=0.000000
                end_time=12.500000
                TAG:title=Opening
                [/CHAPTER]
                [CHAPTER]
                start_time=12.500000
                end_time=42.000000
                TAG:title=Second
                [/CHAPTER]
                [FORMAT]
                duration=42.000000
                [/FORMAT]
                """;
        AudioTrack track = FfmpegAudioPlaybackBackend.parseProbe(Path.of("sample.m4b"), output);
        assertThat(track.durationMillis()).isEqualTo(42_000L);
        assertThat(track.chapters()).extracting(AudioChapter::title).containsExactly("Opening", "Second");
        assertThat(track.chapterIndexAt(20_000L)).isEqualTo(1);
    }

    @Test void fallsBackToOneSyntheticChapterWhenContainerHasNone() throws Exception {
        AudioTrack track = FfmpegAudioPlaybackBackend.parseProbe(Path.of("sample.mp3"), "[FORMAT]\nduration=3.250\n[/FORMAT]\n");
        assertThat(track.durationMillis()).isEqualTo(3_250L);
        assertThat(track.chapters()).hasSize(1);
    }
}
