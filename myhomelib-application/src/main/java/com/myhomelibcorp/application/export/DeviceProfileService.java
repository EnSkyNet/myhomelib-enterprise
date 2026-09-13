package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Built-in and user-defined removable-reader profiles. */
@Component
@RequiredArgsConstructor
public class DeviceProfileService {
    public static final String GENERIC_FOLDER_ID = "generic-folder";
    public static final String KINDLE_ID = "kindle-usb";
    public static final String KOBO_ID = "kobo";
    public static final String POCKETBOOK_ID = "pocketbook";
    public static final String ANDROID_ID = "android-storage";

    private static final String ORDER = "deviceProfiles.order";
    private static final String PREFIX = "deviceProfiles.profile.";

    private final ApplicationSettingsPort settings;

    public List<DeviceTargetProfile> loadProfiles() {
        List<DeviceTargetProfile> result = new ArrayList<>(builtIns());
        for (String id : splitIds(settings.get(ORDER, ""))) {
            readCustom(id).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    public Optional<DeviceTargetProfile> findById(String id) {
        String normalized = text(id);
        if (normalized.isBlank()) return Optional.empty();
        return builtIns().stream().filter(profile -> profile.id().equals(normalized)).findFirst()
                .or(() -> readCustom(normalized));
    }

    public DeviceTargetProfile genericFolder() {
        return findById(GENERIC_FOLDER_ID).orElseThrow();
    }

    /**
     * Best-effort filesystem detection. Unknown roots deliberately fall back to the generic folder
     * profile rather than guessing a device family.
     */
    public DeviceTargetProfile detectOrGeneric(Path mountRoot) {
        if (mountRoot == null) return genericFolder();
        Path root = mountRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return genericFolder();
        if (exists(root, ".kobo")) return findById(KOBO_ID).orElseThrow();
        if (exists(root, "documents")) return findById(KINDLE_ID).orElseThrow();
        if (exists(root, "system") && (exists(root, "applications") || exists(root, "Books"))) {
            return findById(POCKETBOOK_ID).orElseThrow();
        }
        if (exists(root, "Android")) return findById(ANDROID_ID).orElseThrow();
        return genericFolder();
    }

    public DeviceTargetProfile newCustomProfile(String name) {
        return new DeviceTargetProfile("custom-" + UUID.randomUUID(), normalizeName(name),
                List.of(ExportRequest.ExportFormat.EPUB, ExportRequest.ExportFormat.FB2, ExportRequest.ExportFormat.PDF),
                "", false);
    }

    public synchronized void saveCustom(DeviceTargetProfile profile) {
        if (profile == null) throw new IllegalArgumentException("Device profile is required");
        if (profile.builtIn() || isBuiltIn(profile.id())) {
            throw new IllegalArgumentException("Built-in device profiles cannot be overwritten");
        }
        String base = PREFIX + profile.id();
        settings.put(base + ".name", profile.displayName());
        settings.put(base + ".formats", profile.preferredFormats().stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(""));
        settings.put(base + ".destination", profile.destinationSubfolder());
        List<String> order = splitIds(settings.get(ORDER, ""));
        if (!order.contains(profile.id())) order.add(profile.id());
        settings.put(ORDER, String.join(",", order));
    }

    public synchronized void deleteCustom(String id) {
        String normalized = text(id);
        if (normalized.isBlank() || isBuiltIn(normalized)) return;
        String base = PREFIX + normalized + ".";
        new ArrayList<>(settings.findByPrefix(base).keySet()).forEach(settings::remove);
        List<String> order = splitIds(settings.get(ORDER, ""));
        order.remove(normalized);
        settings.put(ORDER, String.join(",", order));
    }

    /** Keeps a manually selected format first, then follows the profile fallback order. */
    public List<ExportRequest.ExportFormat> orderedFormats(DeviceTargetProfile profile,
                                                            ExportRequest.ExportFormat selectedFormat) {
        LinkedHashSet<ExportRequest.ExportFormat> ordered = new LinkedHashSet<>();
        if (selectedFormat != null) ordered.add(selectedFormat);
        DeviceTargetProfile effective = profile == null ? genericFolder() : profile;
        ordered.addAll(effective.preferredFormats());
        return List.copyOf(ordered);
    }

    public static List<DeviceTargetProfile> builtIns() {
        return List.of(
                new DeviceTargetProfile(GENERIC_FOLDER_ID, "Звичайна папка",
                        List.of(ExportRequest.ExportFormat.EPUB, ExportRequest.ExportFormat.FB2,
                                ExportRequest.ExportFormat.PDF, ExportRequest.ExportFormat.MOBI,
                                ExportRequest.ExportFormat.TXT), "", true),
                new DeviceTargetProfile(KINDLE_ID, "Kindle (USB)",
                        List.of(ExportRequest.ExportFormat.MOBI, ExportRequest.ExportFormat.PDF,
                                ExportRequest.ExportFormat.TXT), "documents", true),
                new DeviceTargetProfile(KOBO_ID, "Kobo",
                        List.of(ExportRequest.ExportFormat.EPUB, ExportRequest.ExportFormat.PDF), "", true),
                new DeviceTargetProfile(POCKETBOOK_ID, "PocketBook",
                        List.of(ExportRequest.ExportFormat.EPUB, ExportRequest.ExportFormat.FB2,
                                ExportRequest.ExportFormat.PDF, ExportRequest.ExportFormat.MOBI), "Books", true),
                new DeviceTargetProfile(ANDROID_ID, "Android storage",
                        List.of(ExportRequest.ExportFormat.EPUB, ExportRequest.ExportFormat.PDF,
                                ExportRequest.ExportFormat.FB2, ExportRequest.ExportFormat.TXT), "Books", true)
        );
    }

    private Optional<DeviceTargetProfile> readCustom(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,80}") || isBuiltIn(id)) return Optional.empty();
        String base = PREFIX + id;
        String name = text(settings.get(base + ".name", ""));
        if (name.isBlank()) return Optional.empty();
        List<ExportRequest.ExportFormat> formats = parseFormats(settings.get(base + ".formats", ""));
        if (formats.isEmpty()) formats = List.of(ExportRequest.ExportFormat.EPUB);
        try {
            return Optional.of(new DeviceTargetProfile(id, name, formats,
                    settings.get(base + ".destination", ""), false));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static List<ExportRequest.ExportFormat> parseFormats(String value) {
        List<ExportRequest.ExportFormat> result = new ArrayList<>();
        for (String raw : text(value).split(",")) {
            if (raw.isBlank()) continue;
            try { result.add(ExportRequest.ExportFormat.valueOf(raw.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) { /* corrupt/old token is ignored */ }
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    private static boolean exists(Path root, String child) {
        try { return Files.exists(root.resolve(child)); }
        catch (RuntimeException ignored) { return false; }
    }

    private static boolean isBuiltIn(String id) {
        return Set.of(GENERIC_FOLDER_ID, KINDLE_ID, KOBO_ID, POCKETBOOK_ID, ANDROID_ID).contains(id);
    }

    private static List<String> splitIds(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;
        Arrays.stream(value.split(",")).map(String::trim).filter(v -> v.matches("[A-Za-z0-9._-]{1,80}"))
                .distinct().forEach(result::add);
        return result;
    }

    private static String normalizeName(String name) {
        String normalized = text(name);
        return normalized.isBlank() ? "Власний пристрій" : normalized;
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
