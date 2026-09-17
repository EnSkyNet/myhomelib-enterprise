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
}
