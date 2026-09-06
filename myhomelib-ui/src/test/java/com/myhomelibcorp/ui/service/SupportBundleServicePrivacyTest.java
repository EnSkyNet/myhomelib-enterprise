package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportBundleServicePrivacyTest {
    @TempDir Path temp;

    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @AfterEach
    void restoreProperties() {
        originalProperties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        });
    }

    @Test
    void redactsSyntheticSecretsPathsUrlsAndShowsBundleComposition() throws Exception {
        Path launch = temp.resolve("launch-private-Alice");
        Path data = temp.resolve("home/Alice/.myhomelib");
        Path logs = data.resolve("logs");
        Files.createDirectories(logs);
        Files.createDirectories(launch);
        setProperty("myhomelib.launchDir", launch.toString());
        setProperty("myhomelib.dataDir", data.toString());
        setProperty("user.home", temp.resolve("home/Alice").toString());
        setProperty("jpackage.app-version", "7.1.0-runtime-test");

        Path normalLog = logs.resolve("myhomelib.log");
        Files.writeString(normalLog, """
                login password=hunter2 token=abc123
                opened /home/alice/Books/Very Private Book.fb2
                source=https://user:pass@example.test/private/book?token=abc
                bookTitle=Very Private Book; author=Alice Writer
                contact=alice.private@example.test
                data=%s
                """.formatted(data), StandardCharsets.UTF_8);
        Path oversized = logs.resolve("oversized.log");
        createSparseFile(oversized, SupportBundleService.MAX_LOG_FILE + 1);
        Files.writeString(launch.resolve("RELEASE_VALIDATION.txt"),
                "workspace=" + launch + " url=https://private.example.test/build?token=xyz\n",
                StandardCharsets.UTF_8);

        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.findByPrefix("")).thenReturn(Map.of(
                "network.password", "dont-leak-me",
                "library.root", data.resolve("Books/Hidden Collection").toString(),
                "catalog.url", "https://private.example.test/opds?token=topsecret"));
        SupportBundleService service = new SupportBundleService(settings);

        SupportBundlePreview preview = service.preview(SupportBundleOptions.defaults());
        assertThat(preview.items()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("logs/myhomelib.log");
            assertThat(item.included()).isTrue();
        });
        assertThat(preview.items()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo("logs/oversized.log");
            assertThat(item.included()).isFalse();
        });
        assertThat(preview.displayText()).contains("environment.txt", "settings-redacted.txt", "logs/myhomelib.log");

        Path zip = service.create(temp.resolve("support.zip"), SupportBundleOptions.defaults());
        Map<String, String> entries = textEntries(zip);

        assertThat(entries).containsKeys("environment.txt", "settings-redacted.txt", "threads.txt",
                "release/RELEASE_VALIDATION.txt", "logs/myhomelib.log");
        assertThat(entries).doesNotContainKey("logs/oversized.log");

        String combined = String.join("\n", entries.values());
        assertThat(entries.get("environment.txt"))
                .contains("app.version=7.1.0-runtime-test", "dataDir=<DATA_DIR>", "launchDir=<LAUNCH_DIR>")
                .doesNotContain(data.toString(), launch.toString());
        assertThat(entries.get("settings-redacted.txt"))
                .contains("network.password=<REDACTED>")
                .doesNotContain("dont-leak-me", "private.example.test", "Hidden Collection", data.toString());
        assertThat(entries.get("logs/myhomelib.log"))
                .contains("password=<REDACTED>", "token=<REDACTED>", "<URL_REDACTED>", "<PATH_REDACTED>")
                .doesNotContain("hunter2", "abc123", "Very Private Book", "Alice Writer", "alice.private@example.test");
        assertThat(combined)
                .doesNotContain("topsecret", "private.example.test", data.toString(), launch.toString());
    }

    @Test
    void optionsActuallyRemoveOptionalSections() throws Exception {
        Path launch = temp.resolve("launch");
        Path data = temp.resolve("data");
        Files.createDirectories(data.resolve("logs"));
        Files.createDirectories(launch);
        setProperty("myhomelib.launchDir", launch.toString());
        setProperty("myhomelib.dataDir", data.toString());
        Files.writeString(data.resolve("logs/app.log"), "safe line\n");
        Files.writeString(launch.resolve("ARCHITECTURE.md"), "safe\n");

        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.findByPrefix("")).thenReturn(Map.of());
        SupportBundleService service = new SupportBundleService(settings);
        SupportBundleOptions minimal = new SupportBundleOptions(false, false, false);

        Map<String, String> entries = textEntries(service.create(temp.resolve("minimal.zip"), minimal));
        assertThat(entries.keySet()).containsExactlyInAnyOrder("environment.txt", "settings-redacted.txt");
        assertThat(service.preview(minimal).items())
                .anySatisfy(item -> { if (item.name().equals("threads.txt")) assertThat(item.included()).isFalse(); });
    }

    private void setProperty(String key, String value) {
        originalProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static void createSparseFile(Path path, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(size - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
    }

    private static Map<String, String> textEntries(Path zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                out.put(entry.getName(), bytes.toString(StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
