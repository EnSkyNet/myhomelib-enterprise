package com.myhomelibcorp.infrastructure.dictionary;

import com.myhomelibcorp.application.dictionary.DictionaryQuery;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
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

class LocalDictionaryProviderTest {
    @TempDir Path tempDir;

    @Test
    void customUtf8DictionaryIsUsedOfflineAndCanExtendBundledEntries() throws Exception {
        Path dictionary = tempDir.resolve("dictionary.tsv");
        Files.writeString(dictionary, """
                uk\tкнига\tіменник\tЛокальне користувацьке визначення.\tПриклад із файлу.
                uk\tтест\tіменник\tПеревірка локального словника.\t
                """, StandardCharsets.UTF_8);
        MapSettings settings = new MapSettings();
        settings.put("dictionary.local.path", dictionary.toString());
        LocalDictionaryProvider provider = new LocalDictionaryProvider(settings);

        var entries = provider.lookup(
                DictionaryQuery.of("тест", "uk-UA"),
                TextProviderRequestContext.create(Duration.ofSeconds(1), new AtomicBoolean(false)));

        assertThat(provider.isOffline()).isTrue();
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.headword()).isEqualTo("тест");
            assertThat(entry.definition()).isEqualTo("Перевірка локального словника.");
            assertThat(entry.source()).isEqualTo(dictionary.toAbsolutePath().normalize().toString());
        });
    }

    @Test
    void bundledDictionaryWorksWithoutCustomFile() throws Exception {
        MapSettings settings = new MapSettings();
        settings.put("dictionary.local.path", tempDir.resolve("missing.tsv").toString());
        LocalDictionaryProvider provider = new LocalDictionaryProvider(settings);

        var entries = provider.lookup(
                DictionaryQuery.of("book", "en"),
                TextProviderRequestContext.create(Duration.ofSeconds(1), new AtomicBoolean(false)));

        assertThat(entries).isNotEmpty();
        assertThat(entries.getFirst().providerId()).isEqualTo(LocalDictionaryProvider.PROVIDER_ID);
        assertThat(entries.getFirst().source()).isEqualTo("builtin");
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
