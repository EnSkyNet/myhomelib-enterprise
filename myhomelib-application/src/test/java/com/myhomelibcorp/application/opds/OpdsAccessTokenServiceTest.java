package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpdsAccessTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @Test
    void createsHighEntropyTokenButPersistsOnlyHashAndMetadata() {
        MemorySettings settings = new MemorySettings();
        OpdsAccessTokenService service = service(settings);

        var created = service.create("Laptop", Set.of(OpdsTokenScope.CATALOG_READ, OpdsTokenScope.DOWNLOAD));

        assertThat(created.token()).startsWith("mhl_").hasSizeGreaterThan(50);
        assertThat(created.info().deviceName()).isEqualTo("Laptop");
        assertThat(created.info().scopes()).containsExactlyInAnyOrder(OpdsTokenScope.CATALOG_READ, OpdsTokenScope.DOWNLOAD);
        assertThat(settings.values.values()).noneMatch(value -> value != null && value.contains(created.token()));
        assertThat(settings.values.values()).anyMatch(value -> value != null && value.startsWith("sha256$"));
        assertThat(service.list()).singleElement().satisfies(info -> {
            assertThat(info.id()).isEqualTo(created.info().id());
            assertThat(info.lastUsedAt()).isNull();
            assertThat(info.revoked()).isFalse();
        });
    }

    @Test
    void authenticationUpdatesLastUsedAndEnforcesScopes() {
        MemorySettings settings = new MemorySettings();
        OpdsAccessTokenService service = service(settings);
        var created = service.create("Reader", Set.of(OpdsTokenScope.CATALOG_READ));

        assertThat(service.authorize(created.token(), OpdsTokenScope.CATALOG_READ))
                .isEqualTo(OpdsAccessTokenService.Authorization.AUTHORIZED);
        assertThat(service.list().getFirst().lastUsedAt()).isEqualTo(NOW);
        assertThat(service.authorize(created.token(), OpdsTokenScope.DOWNLOAD))
                .isEqualTo(OpdsAccessTokenService.Authorization.INSUFFICIENT_SCOPE);
    }

    @Test
    void revokedTokenFailsImmediatelyAndSurvivesServiceReload() {
        MemorySettings settings = new MemorySettings();
        OpdsAccessTokenService service = service(settings);
        var created = service.create("Phone", Set.of(OpdsTokenScope.CATALOG_READ));
        OpdsAccessTokenService reloaded = service(settings);

        assertThat(reloaded.authenticate(created.token(), OpdsTokenScope.CATALOG_READ)).isTrue();
        assertThat(reloaded.revoke(created.info().id())).isTrue();
        assertThat(reloaded.authenticate(created.token(), OpdsTokenScope.CATALOG_READ)).isFalse();
        assertThat(reloaded.list().getFirst().revoked()).isTrue();
    }

    @Test
    void malformedOrUnknownTokensFailClosed() {
        OpdsAccessTokenService service = service(new MemorySettings());
        assertThat(service.authenticate("", OpdsTokenScope.CATALOG_READ)).isFalse();
        assertThat(service.authenticate("mhl_unknown_secret", OpdsTokenScope.CATALOG_READ)).isFalse();
        assertThat(service.authenticate("Bearer not-a-token", OpdsTokenScope.CATALOG_READ)).isFalse();
    }

    private static OpdsAccessTokenService service(MemorySettings settings) {
        return new OpdsAccessTokenService(settings, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
