package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelpServiceBoundedLoadingTest {
    @Test
    void exactHelpSizeLimitIsAcceptedWithoutTruncation() throws Exception {
        byte[] bytes = new byte[HelpService.MAX_HELP_BYTES];
        Arrays.fill(bytes, (byte) 'x');

        String value = HelpService.readHelpText(new ByteArrayInputStream(bytes));

        assertThat(value).hasSize(HelpService.MAX_HELP_BYTES);
        assertThat(value.charAt(value.length() - 1)).isEqualTo('x');
    }

    @Test
    void oversizedHelpIsRejectedRatherThanAllocatingEntireInput() {
        byte[] bytes = new byte[HelpService.MAX_HELP_BYTES + 1];
        Arrays.fill(bytes, (byte) 'a');

        assertThatThrownBy(() -> HelpService.readHelpText(new ByteArrayInputStream(bytes)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("maximum allowed size");
    }

    @Test
    void firstMarkdownHeadingIsFoundWithoutCopyingAllLines() {
        String text = "\n \n# Українська довідка\n" + "trailing paragraph\n".repeat(30_000);
        assertThat(HelpService.extractTitle(text, "fallback")).isEqualTo("Українська довідка");
    }

    @Test
    void nonheadingFirstLineRetainsFallbackEvenIfHeadingAppearsLater() {
        assertThat(HelpService.extractTitle("intro\n# Later heading\n", "fallback"))
                .isEqualTo("fallback");
        assertThat(HelpService.extractTitle(null, "fallback")).isEqualTo("fallback");
        assertThat(HelpService.extractTitle("\n  \n", "fallback")).isEqualTo("fallback");
    }

    @Test
    void utf8HelpContentRetainsUkrainianCharacters() throws Exception {
        String original = "# Довідка про книги\nЧитання і пошук";
        String decoded = HelpService.readHelpText(
                new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)));
        assertThat(decoded).isEqualTo(original);
    }
}
