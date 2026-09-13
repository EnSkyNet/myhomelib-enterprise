package com.myhomelibcorp.infrastructure.translation;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.application.translation.TranslationQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPhraseTranslationProviderTest {
    @TempDir Path tempDir;

    @Test
    void translatesCustomUtf8PhraseWithoutNetwork() throws Exception {
        Path phrases = tempDir.resolve("translations.tsv");
        Files.writeString(phrases, "uk\ten\tдобрий вечір\tgood evening\n", StandardCharsets.UTF_8);
        MapSettings settings = new MapSettings();
        settings.put("translation.local.path", phrases.toString());
        LocalPhraseTranslationProvider provider = new LocalPhraseTranslationProvider(settings);

        var result = provider.translate(
                TranslationQuery.autoDetect("добрий   вечір", "en"),
                TextProviderRequestContext.create(Duration.ofSeconds(1), new AtomicBoolean(false)));

        assertThat(provider.isRemote()).isFalse();
        assertThat(result.providerId()).isEqualTo(LocalPhraseTranslationProvider.PROVIDER_ID);
        assertThat(result.translatedText()).isEqualTo("good evening");
    }

    @Test
    void bundledPhraseTableWorksAndMissingPhraseIsNotFound() throws Exception {
        MapSettings settings = new MapSettings();
        settings.put("translation.local.path", tempDir.resolve("missing.tsv").toString());
        LocalPhraseTranslationProvider provider = new LocalPhraseTranslationProvider(settings);
        TextProviderRequestContext context = TextProviderRequestContext.create(
                Duration.ofSeconds(1), new AtomicBoolean(false));

        assertThat(provider.translate(TranslationQuery.autoDetect("книга", "en"), context).translatedText())
                .isEqualTo("book");

        assertThatThrownBy(() -> provider.translate(
                TranslationQuery.autoDetect("фраза, якої немає", "en"),
                TextProviderRequestContext.create(Duration.ofSeconds(1), new AtomicBoolean(false))))
                .isInstanceOf(TextProviderException.class)
                .satisfies(error -> assertThat(((TextProviderException) error).kind())
                        .isEqualTo(TextProviderErrorKind.NOT_FOUND));
    }

    private static final class MapSettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
