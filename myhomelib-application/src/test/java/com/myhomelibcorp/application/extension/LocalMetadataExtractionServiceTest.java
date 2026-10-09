package com.myhomelibcorp.application.extension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMetadataExtractionServiceTest {
    @TempDir Path temp;

    @Test
    void usesRuntimeExtractorsWithoutMutatingAnything() throws Exception {
        Path file = temp.resolve("book.demo");
        Files.writeString(file, "content");
        RuntimeExtensionRegistry registry = new RuntimeExtensionRegistry();
        LocalMetadataExtractor extractor = new LocalMetadataExtractor() {
            @Override public String id() { return "demo"; }
            @Override public boolean supports(Path source) { return source.getFileName().toString().endsWith(".demo"); }
            @Override public Map<String, String> extract(Path source) { return Map.of("title", "Назва", "author", "Автор"); }
        };
        registry.replacePlugin("metadata.demo", new RuntimeExtensionRegistry.ExtensionBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(extractor)));

        LocalMetadataExtractionService service = new LocalMetadataExtractionService(registry);
        assertThat(service.hasExtractors()).isTrue();
        assertThat(service.extract(file)).singleElement().satisfies(result -> {
            assertThat(result.providerId()).isEqualTo("demo");
            assertThat(result.values()).containsEntry("title", "Назва").containsEntry("author", "Автор");
        });
        assertThat(Files.readString(file)).isEqualTo("content");
    }
    @Test
    void truncationDoesNotSplitSurrogatePairInUserVisiblePluginMetadata() throws Exception {
        Path file = temp.resolve("unicode.demo");
        Files.writeString(file, "content");
        String longTitle = "a".repeat(16_383) + "😀tail";
        RuntimeExtensionRegistry registry = new RuntimeExtensionRegistry();
        LocalMetadataExtractor extractor = new LocalMetadataExtractor() {
            @Override public String id() { return "unicode"; }
            @Override public boolean supports(Path source) { return true; }
            @Override public Map<String, String> extract(Path source) { return Map.of("title", longTitle); }
        };
        registry.replacePlugin("metadata.unicode", new RuntimeExtensionRegistry.ExtensionBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(extractor)));

        String displayed = new LocalMetadataExtractionService(registry).extract(file).getFirst().values().get("title");

        assertThat(displayed).endsWith("…");
        assertThat(hasUnpairedSurrogate(displayed)).isFalse();
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

}
