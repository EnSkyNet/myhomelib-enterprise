package com.myhomelibcorp.shared.text;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TextStreamDecoderTest {
    @Test
    void readsUtf8WhenDetectionSampleEndsInsideMultibyteCharacter() throws Exception {
        for (String symbol : new String[]{"ї", "€", "😀"}) {
            for (int split = 1; split < symbol.getBytes(StandardCharsets.UTF_8).length; split++) {
                String expected = "a".repeat(16 * 1024 - split) + symbol + " український текст";
                try (var reader = TextStreamDecoder.open(new ByteArrayInputStream(expected.getBytes(StandardCharsets.UTF_8)), null)) {
                    assertThat(reader.readLine()).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void readsUtf8BomAndLegacyCyrillic() throws Exception {
        byte[] utf8 = "\uFEFFПривіт".getBytes(StandardCharsets.UTF_8);
        try (var reader = TextStreamDecoder.open(new ByteArrayInputStream(utf8), null)) {
            assertThat(reader.readLine()).isEqualTo("Привіт");
        }

        byte[] cp1251 = "Привет світ".getBytes(Charset.forName("windows-1251"));
        try (var reader = TextStreamDecoder.open(new ByteArrayInputStream(cp1251), null)) {
            assertThat(reader.readLine()).isEqualTo("Привет світ");
        }
    }
}
