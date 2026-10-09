package com.myhomelibcorp.integration;

import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.ai.AiProvider;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.PortableUserDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DesktopIntegrationBackendLazyInitializationTest {
    @TempDir Path tempDir;

    @Test
    void startupDoesNotResolveOptionalWebDavOrAiDependenciesOrCreateDeviceId() {
        String oldDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", tempDir.resolve("data").toString());
        try {
            FakeSettings settings = new FakeSettings();
            AtomicInteger portableCalls = new AtomicInteger();
            AtomicInteger mapperCalls = new AtomicInteger();
            AtomicInteger aiCalls = new AtomicInteger();

            Supplier<PortableUserDataService> portable = () -> {
                portableCalls.incrementAndGet();
                return mock(PortableUserDataService.class);
            };
            Supplier<ObjectMapper> mapper = () -> {
                mapperCalls.incrementAndGet();
                return new ObjectMapper();
            };
            Supplier<List<AiProvider>> aiProviders = () -> {
                aiCalls.incrementAndGet();
                return List.of();
            };

            new DesktopIntegrationBackend(settings, portable, new RuntimeExtensionRegistry(), mapper, aiProviders,
                    null, null);

            assertThat(portableCalls).hasValue(0);
            assertThat(mapperCalls).hasValue(0);
            assertThat(aiCalls).hasValue(0);
            assertThat(settings.values).doesNotContainKey("sync.deviceId");
        } finally {
            if (oldDataDir == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", oldDataDir);
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
