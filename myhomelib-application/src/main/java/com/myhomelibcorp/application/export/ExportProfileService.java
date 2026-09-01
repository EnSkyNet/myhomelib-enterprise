package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Application-owned persistence for named conversion/export profiles. */
@Component
@RequiredArgsConstructor
public class ExportProfileService {
    private static final String ORDER = "exportProfiles.order";
    private static final String PREFIX = "exportProfiles.profile.";
    private static final String MIGRATION = "exportProfiles.migration.legacy.v1";
    private static final String PATH_LAYOUT_MIGRATION = "exportProfiles.migration.deviceLayout.v2";
    private final ApplicationSettingsPort settings;

    public synchronized List<ExportProfile> loadProfiles() {
        migrateLegacyOnce();
        migrateCanonicalDeviceLayoutOnce();
        Map<String,String> values = settings.findByPrefix(PREFIX);
        List<String> ids = splitIds(settings.get(ORDER, ""));
        values.keySet().stream().filter(k -> k.endsWith(".name"))
                .map(k -> k.substring(PREFIX.length(), k.length() - 5))
                .filter(ExportProfileService::validId).sorted()
                .forEach(id -> { if (!ids.contains(id)) ids.add(id); });
        List<ExportProfile> result = new ArrayList<>();
        for (String id : ids) read(id).ifPresent(result::add);
        return List.copyOf(result);
    }

    public synchronized Optional<ExportProfile> findById(String id) {
        migrateLegacyOnce();
        migrateCanonicalDeviceLayoutOnce();
        return validId(id) ? read(id) : Optional.empty();
    }

    public ExportProfile newProfile(String name) {
        return new ExportProfile(UUID.randomUUID().toString(), normalizeName(name),
                ExportRequest.ExportFormat.FB2, "", ExportRequest.CollisionPolicy.RENAME,
                false, "%n2 - %t", "%a/%s", "");
    }

    public synchronized void save(ExportProfile profile) {
        validate(profile);
        String base = PREFIX + profile.id();
        settings.put(base + ".name", profile.name());
        settings.put(base + ".format", profile.format() == null ? "" : profile.format().name());
        settings.put(base + ".destination", profile.destinationFolder());
        settings.put(base + ".collision", profile.collisionPolicy().name());
        settings.putBoolean(base + ".extractOnly", profile.extractOnly());
        settings.put(base + ".filenameTemplate", profile.filenameTemplate());
        settings.put(base + ".subfolderTemplate", profile.subfolderTemplate());
        settings.put(base + ".postActionProfileId", profile.postActionProfileId());
        List<String> order = splitIds(settings.get(ORDER, ""));
        if (!order.contains(profile.id())) order.add(profile.id());
        settings.put(ORDER, String.join(",", order));
    }

    public synchronized void delete(String id) {
        if (!validId(id)) return;
        String base = PREFIX + id + ".";
        new ArrayList<>(settings.findByPrefix(base).keySet()).forEach(settings::remove);
        List<String> order = splitIds(settings.get(ORDER, ""));
        order.remove(id);
        settings.put(ORDER, String.join(",", order));
    }

    private Optional<ExportProfile> read(String id) {
        String base = PREFIX + id;
        String name = settings.get(base + ".name", "").trim();
        if (name.isBlank()) return Optional.empty();
        ExportRequest.ExportFormat format = parseEnum(ExportRequest.ExportFormat.class,
                settings.get(base + ".format", "FB2"), ExportRequest.ExportFormat.FB2);
        ExportRequest.CollisionPolicy collision = parseEnum(ExportRequest.CollisionPolicy.class,
                settings.get(base + ".collision", "RENAME"), ExportRequest.CollisionPolicy.RENAME);
        return Optional.of(new ExportProfile(id, name, format,
                settings.get(base + ".destination", ""), collision,
                settings.getBoolean(base + ".extractOnly", false),
                settings.get(base + ".filenameTemplate", "%n2 - %t"),
                settings.get(base + ".subfolderTemplate", "%a/%s"),
                settings.get(base + ".postActionProfileId", "")));
    }

    /** Converts the old global export settings into one editable profile once. */
    private void migrateLegacyOnce() {
        if (settings.getBoolean(MIGRATION, false)) return;
        try {
            if (splitIds(settings.get(ORDER, "")).isEmpty()) {
                String postAction = settings.getBoolean("export.runPostCommand", false) ? "legacy-post-command" : "";
                ExportProfile profile = new ExportProfile(
                        "default-export", "Default export", ExportRequest.ExportFormat.FB2, "",
                        ExportRequest.CollisionPolicy.RENAME, false,
                        settings.get("export.filenameTemplate", "%n2 - %t"),
                        settings.get("export.subfolderTemplate", "%a/%s"), postAction);
                save(profile);
            }
        } finally {
            settings.putBoolean(MIGRATION, true);
        }
    }

    /** Migrates only untouched legacy defaults; explicitly customized profiles are preserved. */
    private synchronized void migrateCanonicalDeviceLayoutOnce() {
        if (settings.getBoolean(PATH_LAYOUT_MIGRATION, false)) return;
        try {
            for (String id : splitIds(settings.get(ORDER, ""))) {
                String base = PREFIX + id;
                String fileTemplate = settings.get(base + ".filenameTemplate", "%a - %t").trim();
                String folderTemplate = settings.get(base + ".subfolderTemplate", "").trim();
                if ((fileTemplate.isBlank() || "%a - %t".equals(fileTemplate)) && folderTemplate.isBlank()) {
                    settings.put(base + ".filenameTemplate", "%n2 - %t");
                    settings.put(base + ".subfolderTemplate", "%a/%s");
                }
            }
            String globalFile = settings.get("export.filenameTemplate", "%a - %t").trim();
            String globalFolder = settings.get("export.subfolderTemplate", "").trim();
            if ((globalFile.isBlank() || "%a - %t".equals(globalFile)) && globalFolder.isBlank()) {
                settings.put("export.filenameTemplate", "%n2 - %t");
                settings.put("export.subfolderTemplate", "%a/%s");
            }
        } finally {
            settings.putBoolean(PATH_LAYOUT_MIGRATION, true);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value == null ? "" : value.trim()); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static List<String> splitIds(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) return out;
        Arrays.stream(value.split(",")).map(String::trim).filter(ExportProfileService::validId).distinct().forEach(out::add);
        return out;
    }

    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9._-]{1,80}"); }
    private static String normalizeName(String name) { String v = name == null ? "" : name.trim(); return v.isBlank() ? "Новий профіль" : v; }
    private static void validate(ExportProfile p) {
        if (p == null || !validId(p.id())) throw new IllegalArgumentException("Некоректний ID export profile");
        if (p.name().isBlank()) throw new IllegalArgumentException("Назва export profile порожня");
        if (p.format() == null) throw new IllegalArgumentException("Формат export profile не задано");
        if (p.collisionPolicy() == null) throw new IllegalArgumentException("Collision policy не задано");
    }
}
