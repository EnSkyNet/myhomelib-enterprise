package com.myhomelibcorp.application.extension;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Safe read-only user workflow for plugin-provided local metadata extractors. */
@Service
public final class LocalMetadataExtractionService {
    private static final int MAX_FIELDS_PER_PROVIDER = 200;
    private static final int MAX_VALUE_CHARS = 16_384;
    private final RuntimeExtensionRegistry extensions;

    public LocalMetadataExtractionService(RuntimeExtensionRegistry extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    public boolean hasExtractors() {
        return !extensions.localMetadataExtractors().isEmpty();
    }

    public List<Extraction> extract(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        Path file = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IOException("Файл не знайдено: " + file);
        List<Extraction> results = new ArrayList<>();
        for (LocalMetadataExtractor extractor : extensions.localMetadataExtractors()) {
            boolean supported;
            try { supported = extractor.supports(file); }
            catch (RuntimeException failure) { continue; }
            if (!supported) continue;
            Map<String, String> raw = extractor.extract(file);
            Map<String, String> sanitized = sanitize(raw);
            results.add(new Extraction(safeId(extractor.id()), sanitized));
        }
        return List.copyOf(results);
    }

    private static Map<String, String> sanitize(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (result.size() >= MAX_FIELDS_PER_PROVIDER) break;
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isBlank()) continue;
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.length() > MAX_VALUE_CHARS) value = value.substring(0, MAX_VALUE_CHARS) + "…";
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private static String safeId(String id) {
        String value = id == null ? "" : id.trim();
        return value.isBlank() ? "metadata-extractor" : value;
    }

    public record Extraction(String providerId, Map<String, String> values) {
        public Extraction {
            providerId = providerId == null ? "" : providerId;
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }
}
