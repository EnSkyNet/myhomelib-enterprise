package com.myhomelibcorp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EarlyLocaleBootstrapMemoryTest {
    @TempDir Path temp;

    @Test
    void readsFirstNonblankLanguageIgnoringComments() throws Exception {
        Path language = temp.resolve("language.txt");
        Files.writeString(language, "# configured at setup\r\n \r\n en-US \n uk", StandardCharsets.UTF_8);
        assertThat(MyHomeLibApp.readEarlyLanguageTag(language)).isEqualTo("en-US");
    }

    @Test
    void missingLanguageFileUsesUkrainianDefault() {
        assertThat(MyHomeLibApp.readEarlyLanguageTag(temp.resolve("missing.txt"))).isEqualTo("uk");
    }

    @Test
    void oversizedLanguageFileCannotBeLoadedBeforeSplash() throws Exception {
        Path language = temp.resolve("language.txt");
        Files.writeString(language, "en-US\n" + "x".repeat(MyHomeLibApp.MAX_EARLY_LANGUAGE_FILE_BYTES),
                StandardCharsets.UTF_8);
        assertThat(Files.size(language)).isGreaterThan(MyHomeLibApp.MAX_EARLY_LANGUAGE_FILE_BYTES);
        assertThat(MyHomeLibApp.readEarlyLanguageTag(language)).isEqualTo("uk");
        assertThat(Files.size(language)).isGreaterThan(MyHomeLibApp.MAX_EARLY_LANGUAGE_FILE_BYTES);
    }

    @Test
    void exactMaximumFileSizeRemainsReadable() throws Exception {
        Path language = temp.resolve("language.txt");
        byte[] content = ("pl-PL\n" + "#".repeat(MyHomeLibApp.MAX_EARLY_LANGUAGE_FILE_BYTES - 6))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(content).hasSize(MyHomeLibApp.MAX_EARLY_LANGUAGE_FILE_BYTES);
        Files.write(language, content);
        assertThat(MyHomeLibApp.readEarlyLanguageTag(language)).isEqualTo("pl-PL");
    }

    @Test
    void emptyAndCommentOnlyFilesUseDefault() throws Exception {
        Path language = temp.resolve("language.txt");
        Files.writeString(language, "# note\r\n  \n# another\n", StandardCharsets.UTF_8);
        assertThat(MyHomeLibApp.readEarlyLanguageTag(language)).isEqualTo("uk");
    }
}
