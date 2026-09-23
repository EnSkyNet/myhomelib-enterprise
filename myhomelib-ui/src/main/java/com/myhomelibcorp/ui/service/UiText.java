package com.myhomelibcorp.ui.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * Static localization facade for legacy/non-managed UI helpers.
 *
 * <p>New Spring-managed code should prefer constructor-injected {@link LocalizationService}.
 * This facade exists so user-facing text in static helpers, presenters, and legacy controllers
 * can still be addressed only by stable catalog keys instead of embedding display strings in
 * Java source. Once Spring is ready it delegates to the active language service. Before that
 * (mainly isolated tests/static initialization) it resolves the same key from the bundled
 * Ukrainian language file, never from a hardcoded display-string fallback.</p>
 */
@Component
public final class UiText {
    private static volatile LocalizationService localization;
    private static final Map<String, String> BUNDLED_UK = loadBundledUkrainian();

    public UiText(LocalizationService localizationService) {
        localization = localizationService;
    }

    public static String text(String key) {
        if (key == null) return null;
        LocalizationService service = localization;
        if (service != null) return service.text(key);
        return BUNDLED_UK.getOrDefault(key, key);
    }

    public static String format(String key, Object... args) {
        return LocalizationService.formatPattern(text(key), args);
    }

    private static Map<String, String> loadBundledUkrainian() {
        try (InputStream in = UiText.class.getResourceAsStream("/lang/default/uk.json")) {
            if (in == null) return Map.of();
            Map<String, Object> root = new ObjectMapper().readValue(in, new TypeReference<>() {});
            Object value = root.get("translations");
            if (!(value instanceof Map<?, ?> raw)) return Map.of();
            java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k != null && v != null) result.put(String.valueOf(k), String.valueOf(v));
            });
            return Collections.unmodifiableMap(result);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

}
