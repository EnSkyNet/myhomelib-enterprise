package com.myhomelibcorp.infrastructure.reader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Canonical JSON compatibility codec for global and per-book Reader preferences. */
@Component
public class ReaderPreferencesJsonCodec {
    public static final long MAX_JSON_BYTES = 1024L * 1024L;

    private final ObjectMapper objectMapper;

    public ReaderPreferencesJsonCodec(ObjectMapper objectMapper) {
        // Jackson 3 no longer treats final fields/getters as mutators by default,
        // while the persisted ReaderPreferences format historically relied on the
        // Jackson 2 behavior for Lombok @Value objects. Keep that compatibility
        // local to this legacy-compatible codec instead of weakening every mapper.
        this.objectMapper = objectMapper.rebuild()
                .enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS, MapperFeature.USE_GETTERS_AS_SETTERS)
                .build();
    }

    public ReaderPreferences decode(JsonNode input) throws IOException {
        if (input == null || !input.isObject()) return ReaderPreferences.builder().build();
        ObjectNode merged = objectMapper.valueToTree(ReaderPreferences.builder().build());
        input.properties().forEach(entry -> {
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
