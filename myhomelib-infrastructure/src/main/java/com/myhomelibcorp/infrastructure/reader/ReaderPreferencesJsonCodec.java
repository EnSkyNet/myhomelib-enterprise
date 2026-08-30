package com.myhomelibcorp.infrastructure.reader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Canonical JSON compatibility codec for global and per-book Reader preferences. */
@Component
@RequiredArgsConstructor
public class ReaderPreferencesJsonCodec {
    public static final long MAX_JSON_BYTES = 1024L * 1024L;

    private final ObjectMapper objectMapper;

    public ReaderPreferences decode(JsonNode input) throws IOException {
        if (input == null || !input.isObject()) return ReaderPreferences.builder().build();
        ObjectNode merged = objectMapper.valueToTree(ReaderPreferences.builder().build());
        input.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) merged.set(entry.getKey(), entry.getValue());
        });
        return objectMapper.treeToValue(merged, ReaderPreferences.class);
    }

    public ReaderPreferences decode(String json) throws IOException {
        if (json == null || json.isBlank()) return ReaderPreferences.builder().build();
        return decode(objectMapper.readTree(json));
    }

    public String encode(ReaderPreferences preferences) throws IOException {
        return objectMapper.writeValueAsString(preferences != null ? preferences : ReaderPreferences.builder().build());
    }
}
