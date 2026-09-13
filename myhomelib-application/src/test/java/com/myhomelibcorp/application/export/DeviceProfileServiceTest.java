package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceProfileServiceTest {
    @TempDir Path temp;

    @Test
    void detectsKnownDevicesAndFallsBackToGenericFolder() throws Exception {
        DeviceProfileService service = new DeviceProfileService(new FakeSettings());

        Path kobo = Files.createDirectory(temp.resolve("kobo"));
        Files.createDirectory(kobo.resolve(".kobo"));
        assertThat(service.detectOrGeneric(kobo).id()).isEqualTo(DeviceProfileService.KOBO_ID);

        Path kindle = Files.createDirectory(temp.resolve("kindle"));
        Files.createDirectory(kindle.resolve("documents"));
        assertThat(service.detectOrGeneric(kindle).id()).isEqualTo(DeviceProfileService.KINDLE_ID);

        Path pocketBook = Files.createDirectory(temp.resolve("pocketbook"));
        Files.createDirectory(pocketBook.resolve("system"));
        Files.createDirectory(pocketBook.resolve("Books"));
        assertThat(service.detectOrGeneric(pocketBook).id()).isEqualTo(DeviceProfileService.POCKETBOOK_ID);

        Path android = Files.createDirectory(temp.resolve("android"));
        Files.createDirectory(android.resolve("Android"));
        assertThat(service.detectOrGeneric(android).id()).isEqualTo(DeviceProfileService.ANDROID_ID);

        Path unknown = Files.createDirectory(temp.resolve("unknown"));
        assertThat(service.detectOrGeneric(unknown).id()).isEqualTo(DeviceProfileService.GENERIC_FOLDER_ID);
    }

    @Test
    void customProfilePersistsPreferredOrderAndSafeRelativeDestination() {
        FakeSettings settings = new FakeSettings();
        DeviceProfileService service = new DeviceProfileService(settings);
        DeviceTargetProfile custom = new DeviceTargetProfile("custom-reader", "Reader",
                List.of(ExportRequest.ExportFormat.FB2, ExportRequest.ExportFormat.EPUB), "Books/Fiction", false);

        service.saveCustom(custom);

        assertThat(service.findById("custom-reader")).contains(custom);
        assertThat(service.loadProfiles()).contains(custom);
        assertThat(custom.resolveDestination(temp)).isEqualTo(temp.resolve("Books/Fiction").toAbsolutePath().normalize());
        assertThat(service.orderedFormats(custom, ExportRequest.ExportFormat.PDF))
                .containsExactly(ExportRequest.ExportFormat.PDF, ExportRequest.ExportFormat.FB2, ExportRequest.ExportFormat.EPUB);
    }

    @Test
    void destinationCannotEscapeSelectedRoot() {
        assertThatThrownBy(() -> new DeviceTargetProfile("bad", "Bad",
                List.of(ExportRequest.ExportFormat.EPUB), "../outside", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeSettings implements ApplicationSettingsPort {
        private final Map<String,String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String,String> findByPrefix(String prefix) {
            Map<String,String> result = new LinkedHashMap<>();
            values.forEach((k,v) -> { if (k.startsWith(prefix)) result.put(k,v); });
            return result;
        }
    }
}
