package com.myhomelibcorp.integration;

import com.myhomelibcorp.plugin.api.PluginService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopIntegrationSupportTest {
    @TempDir Path temp;

    @Test
    void validatesAndCanonicalizesWebDavEndpoints() {
        assertThat(DesktopIntegrationSupport.validateWebDavEndpoint("https://example.com/dav" ).toString())
                .isEqualTo("https://example.com/dav/");
        assertThat(DesktopIntegrationSupport.validateWebDavEndpoint("http://127.0.0.1:8080/dav").toString())
                .isEqualTo("http://127.0.0.1:8080/dav/");
        assertThat(DesktopIntegrationSupport.sameEndpoint(
                "https://example.com/dav", "https://example.com/dav/")).isTrue();
        assertThatThrownBy(() -> DesktopIntegrationSupport.validateWebDavEndpoint("http://example.com/dav"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DesktopIntegrationSupport.validateWebDavEndpoint("https://user:secret@example.com/dav"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodesSnapshotsWithBoundedRoundTrip() throws Exception {
        byte[] original = "portable-user-data".repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String encoded = DesktopIntegrationSupport.gzipBase64(original);

        assertThat(DesktopIntegrationSupport.gunzipBase64(encoded, original.length, "too large"))
                .isEqualTo(original);
        assertThatThrownBy(() -> DesktopIntegrationSupport.gunzipBase64(encoded, 10, "too large"))
                .isInstanceOf(IOException.class).hasMessage("too large");
    }

    @Test
    void safeMessageDoesNotSplitUnicodeSequenceAtLimit() {
        String surrogate = "a".repeat(599) + "😀" + "tail";
        String combining = "a".repeat(599) + "e\u0301" + "tail";

        String surrogateCut = DesktopIntegrationSupport.safeMessage(new IllegalStateException(surrogate));
        String combiningCut = DesktopIntegrationSupport.safeMessage(new IllegalStateException(combining));

        assertThat(Character.isHighSurrogate(surrogateCut.charAt(surrogateCut.length() - 1))).isFalse();
        assertThat(combiningCut).doesNotEndWith("e");
    }


    @Test
    void safeMessageHandlesCyclicCausesWithoutLosingSanitization() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("leaf\r\nmarker");
        first.initCause(second);
        second.initCause(first);

        assertThat(DesktopIntegrationSupport.safeMessage(first)).isEqualTo("leaf marker");
        assertThat(DesktopIntegrationSupport.safeMessage(null)).isEqualTo("Невідома помилка");
    }

    @Test
    void parsesPluginFieldsAndBoundsFileReads() throws Exception {
        assertThat(DesktopIntegrationSupport.parseEnumSet("AI_PROVIDER, DEVICE_PROVIDER", PluginService.class))
                .containsExactlyInAnyOrder(PluginService.AI_PROVIDER, PluginService.DEVICE_PROVIDER);
        assertThat(DesktopIntegrationSupport.safeId("  provider id/? ", "fallback"))
                .isEqualTo("provider-id--");
        assertThat(DesktopIntegrationSupport.sanitizeFilePart("1.0 beta/rc")).isEqualTo("1.0_beta_rc");

        Path file = temp.resolve("sample.bin");
        Files.write(file, new byte[] {1, 2, 3, 4});
        assertThat(DesktopIntegrationSupport.readBounded(file, 4, "large")).containsExactly(1, 2, 3, 4);
        assertThatThrownBy(() -> DesktopIntegrationSupport.readBounded(file, 3, "large"))
                .isInstanceOf(IOException.class).hasMessage("large");
    }
}
