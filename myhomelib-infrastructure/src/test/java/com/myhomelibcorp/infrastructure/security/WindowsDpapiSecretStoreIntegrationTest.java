package com.myhomelibcorp.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.WINDOWS)
class WindowsDpapiSecretStoreIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void currentUserDpapiRoundTripPersistsOnlyProtectedBlob() throws Exception {
        WindowsDpapiSecretStore store = new WindowsDpapiSecretStore(tempDir);
        String secret = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

        store.write("credential-master-key-v1", secret);

        assertThat(store.read("credential-master-key-v1")).contains(secret);
        Path blob = tempDir.resolve("credential-key.dpapi");
        assertThat(blob).exists();
        assertThat(Files.readString(blob, StandardCharsets.US_ASCII)).doesNotContain(secret);

        store.delete("credential-master-key-v1");
        assertThat(blob).doesNotExist();
    }
}
