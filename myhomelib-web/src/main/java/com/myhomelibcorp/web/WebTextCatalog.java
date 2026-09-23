package com.myhomelibcorp.web;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * File-backed localization catalogue for the embedded Web Library / OPDS UI.
 * The browser language is selected from {@code Accept-Language}; Ukrainian is
 * used as a deterministic fallback when a supported language cannot be chosen.
 */
public final class WebTextCatalog {
    public static final String DEFAULT_LANGUAGE = "uk";
    private static final List<String> SUPPORTED = List.of("uk", "en", "bg");

    private final Map<String, Map<String, String>> catalogues;

    public WebTextCatalog() {
        this.catalogues = loadCatalogues();
    }

    public String resolveLanguage(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return DEFAULT_LANGUAGE;
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            List<Locale> supportedLocales = SUPPORTED.stream().map(Locale::forLanguageTag).toList();
            Locale matched = Locale.lookup(ranges, supportedLocales);
            if (matched != null && SUPPORTED.contains(matched.getLanguage())) return matched.getLanguage();
        } catch (IllegalArgumentException ignored) {
            // Malformed client header: fall back deterministically below.
        }
        String normalized = acceptLanguage.trim().toLowerCase(Locale.ROOT);
        for (String language : SUPPORTED) {
            if (normalized.equals(language) || normalized.startsWith(language + "-") || normalized.startsWith(language + ",")) {
                return language;
            }
        }
        return DEFAULT_LANGUAGE;
    }

    public String languageTag(String language) {
        String normalized = normalizeLanguage(language);
        return SUPPORTED.contains(normalized) ? normalized : DEFAULT_LANGUAGE;
    }

    public String text(String language, String key, Object... arguments) {
        Objects.requireNonNull(key, "key");
        String normalized = languageTag(language);
        String pattern = catalogues.getOrDefault(normalized, Map.of()).get(key);
        if (pattern == null) pattern = catalogues.getOrDefault(DEFAULT_LANGUAGE, Map.of()).get(key);
        if (pattern == null) return key;
        if (arguments == null || arguments.length == 0) return pattern;
        MessageFormat format = new MessageFormat(pattern, Locale.forLanguageTag(normalized));
        return format.format(arguments);
    }

    public Map<String, String> entries(String language) {
        return catalogues.getOrDefault(languageTag(language), Map.of());
    }

    private static Map<String, Map<String, String>> loadCatalogues() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String language : SUPPORTED) result.put(language, load(language));
        Map<String, String> baseline = result.get(DEFAULT_LANGUAGE);
        for (String language : SUPPORTED) {
            if (!result.get(language).keySet().equals(baseline.keySet())) {
                throw new IllegalStateException("Web localization keys differ for " + language);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> load(String language) {
        String resource = "/web-lang/" + language + ".properties";
        Properties properties = new Properties();
        try (var stream = WebTextCatalog.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing web localization resource: " + resource);
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read web localization resource: " + resource, e);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) values.put(name, properties.getProperty(name));
        return Map.copyOf(values);
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) return DEFAULT_LANGUAGE;
        String tag = language.trim().replace('_', '-');
        Locale locale = Locale.forLanguageTag(tag);
        return locale.getLanguage().isBlank() ? DEFAULT_LANGUAGE : locale.getLanguage().toLowerCase(Locale.ROOT);
    }
}
