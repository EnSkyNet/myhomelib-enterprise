package com.myhomelibcorp.integration;

import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiProvider;
import com.myhomelibcorp.application.ai.AiProviderCapabilities;
import com.myhomelibcorp.application.ai.AiProviderContext;
import com.myhomelibcorp.application.ai.AiProviderException;
import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.ai.AiResponse;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.PortableUserDataService;
import com.myhomelibcorp.infrastructure.ai.OpenAiResponsesProvider;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DesktopIntegrationBackendAiCharacterizationTest {
    @TempDir Path tempDir;

    @Test
    void builtInProvidersKeepPriorityOverRuntimeDuplicatesAndPresentationIsSortedByDisplayName() {
        withDataDir(() -> {
            FakeSettings settings = new FakeSettings();
            RuntimeExtensionRegistry runtime = new RuntimeExtensionRegistry();
            AiProvider builtInZulu = provider("shared", "Zulu", true, Set.of("api-key"));
            AiProvider builtInAlpha = provider("alpha", "Alpha", false, Set.of());
            AiProvider runtimeDuplicate = provider("shared", "A duplicate that must not shadow", false, Set.of());
            AiProvider runtimeBeta = provider("beta", "Beta", false, Set.of());
            runtime.replacePlugin("runtime.ai", new RuntimeExtensionRegistry.ExtensionBundle(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(runtimeDuplicate, runtimeBeta), List.of(), List.of()));

            DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                    settings, mock(PortableUserDataService.class), runtime, new ObjectMapper(),
                    List.of(builtInZulu, builtInAlpha));

            assertThat(backend.aiProviders())
                    .extracting(info -> info.id() + ":" + info.displayName())
                    .containsExactly("alpha:Alpha", "beta:Beta", "shared:Zulu");
            assertThat(backend.aiProviders().get(2).networkRequired()).isTrue();
            assertThat(backend.aiProviders().get(2).requiredSecrets()).containsExactly("api-key");
        });
    }

    @Test
    void invalidAiConfigurationDoesNotPartiallyMutateSettingsAndValidConfigurationIsTrimmed() {
        withDataDir(() -> {
            FakeSettings settings = new FakeSettings();
            settings.put(OpenAiResponsesProvider.ENDPOINT_KEY, "https://old.example/v1/responses");
            settings.put(OpenAiResponsesProvider.MODEL_KEY, "old-model");
            settings.putInt(OpenAiResponsesProvider.MAX_OUTPUT_TOKENS_KEY, 1234);

            DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                    settings, mock(PortableUserDataService.class), new RuntimeExtensionRegistry(),
                    new ObjectMapper(), List.of());

            var invalid = backend.saveAiConfiguration("https://api.openai.com/v1/responses", "  ", 2000, "");
            assertThat(invalid.success()).isFalse();
            assertThat(settings.get(OpenAiResponsesProvider.ENDPOINT_KEY, "")).isEqualTo("https://old.example/v1/responses");
            assertThat(settings.get(OpenAiResponsesProvider.MODEL_KEY, "")).isEqualTo("old-model");
            assertThat(settings.getInt(OpenAiResponsesProvider.MAX_OUTPUT_TOKENS_KEY, -1)).isEqualTo(1234);

            var valid = backend.saveAiConfiguration("  https://api.openai.com/v1/responses  ", "  gpt-test  ", 4096, "");
            assertThat(valid.success()).isTrue();
            assertThat(settings.get(OpenAiResponsesProvider.ENDPOINT_KEY, "")).isEqualTo("https://api.openai.com/v1/responses");
            assertThat(settings.get(OpenAiResponsesProvider.MODEL_KEY, "")).isEqualTo("gpt-test");
            assertThat(settings.getInt(OpenAiResponsesProvider.MAX_OUTPUT_TOKENS_KEY, -1)).isEqualTo(4096);
        });
    }

    private static AiProvider provider(String id, String name, boolean network, Set<String> secrets) {
        return new AiProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return name; }
            @Override public AiProviderCapabilities capabilities() {
                return new AiProviderCapabilities(Set.of(AiOperation.SUMMARY), network, secrets);
            }
            @Override public AiResponse execute(AiRequest request, AiProviderContext context) throws AiProviderException {
                throw new UnsupportedOperationException("characterization only");
            }
        };
    }

    private void withDataDir(Runnable action) {
        String old = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", tempDir.resolve("data").toString());
        try { action.run(); }
        finally {
            if (old == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", old);
        }
    }

    private static final class FakeSettings implements ApplicationSettingsPort {
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
