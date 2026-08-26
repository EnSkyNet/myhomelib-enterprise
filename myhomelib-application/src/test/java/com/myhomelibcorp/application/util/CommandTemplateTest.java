package com.myhomelibcorp.application.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandTemplateTest {
    @Test
    void preservesQuotedWindowsPaths() {
        List<String> args = CommandTemplate.expand(
                "\"C:\\Program Files\\Converter\\tool.exe\" --input \"%SRC%\" --output \"%DST%\"",
                Map.of("%SRC%", "C:\\Books\\My book.fb2", "%DST%", "D:\\Reader\\My book.epub"));

        assertThat(args).containsExactly(
                "C:\\Program Files\\Converter\\tool.exe",
                "--input", "C:\\Books\\My book.fb2",
                "--output", "D:\\Reader\\My book.epub");
    }

    @Test
    void metadataCannotCreateAdditionalArguments() {
        List<String> args = CommandTemplate.expand(
                "tool --title \"%TITLE%\" --file \"%FILE%\"",
                Map.of("%TITLE%", "Book \" --delete-all -- x", "%FILE%", "C:\\A B\\book.fb2"));

        assertThat(args).containsExactly(
                "tool", "--title", "Book \" --delete-all -- x", "--file", "C:\\A B\\book.fb2");
    }

    @Test
    void formatArgumentsRoundTripsQuotesSpacesAndUncPaths() {
        List<String> original = List.of(
                "plain",
                "two words",
                "embedded \"quote\"",
                "\\\\server\\share\\Book Folder\\book.fb2",
                "C:\\Books\\A B\\book.epub",
                "apostrophe's");

        assertThat(CommandTemplate.parse(CommandTemplate.formatArguments(original)))
                .containsExactlyElementsOf(original);
    }

    @Test
    void rejectsUnclosedQuotes() {
        assertThatThrownBy(() -> CommandTemplate.parse("tool \"broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Незакрита");
    }
}
