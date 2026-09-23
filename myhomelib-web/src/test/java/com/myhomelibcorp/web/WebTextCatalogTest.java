package com.myhomelibcorp.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebTextCatalogTest {
    private final WebTextCatalog catalog = new WebTextCatalog();

    @Test void resolvesSupportedAcceptLanguageAndFallsBackToUkrainian() {
        assertThat(catalog.resolveLanguage("en-US,en;q=0.9,uk;q=0.5")).isEqualTo("en");
        assertThat(catalog.resolveLanguage("bg-BG,bg;q=0.9")).isEqualTo("bg");
        assertThat(catalog.resolveLanguage("de-DE,de;q=0.9")).isEqualTo("uk");
        assertThat(catalog.resolveLanguage("broken;q=nope")).isEqualTo("uk");
    }

    @Test void cataloguesHaveIdenticalKeysAndFormatArguments() {
        assertThat(catalog.entries("uk").keySet()).isEqualTo(catalog.entries("en").keySet());
        assertThat(catalog.entries("uk").keySet()).isEqualTo(catalog.entries("bg").keySet());
        assertThat(catalog.text("en", "web.results.count", 42)).isEqualTo("Found: 42");
        assertThat(catalog.text("bg", "web.reader.chapter", 3)).isEqualTo("Глава 3");
    }
}
