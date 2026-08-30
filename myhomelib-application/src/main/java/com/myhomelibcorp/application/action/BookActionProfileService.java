package com.myhomelibcorp.application.action;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.CommandTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persisted named book-action profiles. No UI code touches the settings output port directly. */
@Component
@RequiredArgsConstructor
public class BookActionProfileService {
    private static final String ORDER = "bookActions.order";
    private static final String PREFIX = "bookActions.profile.";
    private static final String MIGRATION = "bookActions.migration.legacyPostCommand.v1";
    private final ApplicationSettingsPort settings;

    public synchronized List<BookActionProfile> loadProfiles() {
        migrateLegacyPostCommandOnce();
        Map<String, String> values = settings.findByPrefix(PREFIX);
        List<String> ids = splitIds(settings.get(ORDER, ""));
        // Recover profiles even if the order key was lost/corrupt.
        values.keySet().stream()
                .filter(k -> k.endsWith(".name"))
                .map(k -> k.substring(PREFIX.length(), k.length() - ".name".length()))
                .filter(BookActionProfileService::validId)
                .sorted()
                .forEach(id -> { if (!ids.contains(id)) ids.add(id); });

        List<BookActionProfile> out = new ArrayList<>();
        for (String id : ids) readProfile(id).ifPresent(out::add);
        return List.copyOf(out);
    }

    public synchronized Optional<BookActionProfile> findById(String id) {
        migrateLegacyPostCommandOnce();
        return validId(id) ? readProfile(id) : Optional.empty();
    }

    public synchronized BookActionProfile newProfile(String name) {
        return new BookActionProfile(UUID.randomUUID().toString(), normalizeName(name), true, List.of());
    }

    public synchronized void save(BookActionProfile profile) {
        validate(profile);
        String base = PREFIX + profile.id();
        settings.put(base + ".name", profile.name());
        settings.putBoolean(base + ".enabled", profile.enabled());
        settings.putInt(base + ".commandCount", profile.commands().size());
        for (int i = 0; i < profile.commands().size(); i++) {
            BookActionCommand command = profile.commands().get(i);
            String c = base + ".command." + i;
            settings.put(c + ".executable", command.executable());
            settings.put(c + ".arguments", command.arguments());
            settings.put(c + ".workingDirectory", command.workingDirectory());
            settings.putBoolean(c + ".waitForExit", command.waitForExit());
        }
        // Remove stale commands left when a profile gets shorter.
        Map<String, String> old = settings.findByPrefix(base + ".command.");
        for (String key : old.keySet()) {
            int index = commandIndex(key, base);
            if (index >= profile.commands().size()) settings.remove(key);
        }
        List<String> order = splitIds(settings.get(ORDER, ""));
        if (!order.contains(profile.id())) order.add(profile.id());
        settings.put(ORDER, String.join(",", order));
    }

    public synchronized void replaceAll(List<BookActionProfile> profiles) {
        List<BookActionProfile> safe = profiles == null ? List.of() : List.copyOf(profiles);
        for (BookActionProfile profile : safe) validate(profile);
        List<String> keep = safe.stream().map(BookActionProfile::id).toList();
        for (BookActionProfile existing : loadProfiles()) {
            if (!keep.contains(existing.id())) deleteProfileKeys(existing.id());
        }
        for (BookActionProfile profile : safe) save(profile);
        settings.put(ORDER, String.join(",", keep));
    }

    public synchronized void delete(String id) {
        if (!validId(id)) return;
        deleteProfileKeys(id);
        List<String> order = splitIds(settings.get(ORDER, ""));
        order.remove(id);
        settings.put(ORDER, String.join(",", order));
    }

    /** Stage 15 compatibility: expose legacy export post-command as a profile once, without deleting legacy settings. */
    private void migrateLegacyPostCommandOnce() {
        if (settings.getBoolean(MIGRATION, false)) return;
        try {
            if (splitIds(settings.get(ORDER, "")).isEmpty()) {
                String legacy = settings.get("export.postCommand", "").trim();
                List<String> tokens = CommandTemplate.parse(legacy);
                if (!tokens.isEmpty()) {
                    String args = CommandTemplate.formatArguments(tokens.subList(1, tokens.size()));
                    BookActionCommand command = new BookActionCommand(tokens.getFirst(), args, "%DIR%", false);
                    BookActionProfile profile = new BookActionProfile(
                            "legacy-post-command", "Legacy post-command",
                            settings.getBoolean("export.runPostCommand", false), List.of(command));
                    save(profile);
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the old export setting untouched; malformed legacy commands are never executed implicitly.
        } finally {
            settings.putBoolean(MIGRATION, true);
        }
    }

    private Optional<BookActionProfile> readProfile(String id) {
        String base = PREFIX + id;
        String name = settings.get(base + ".name", "").trim();
        if (name.isBlank()) return Optional.empty();
        int count = Math.max(0, Math.min(settings.getInt(base + ".commandCount", 0), 64));
        List<BookActionCommand> commands = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String c = base + ".command." + i;
            String executable = settings.get(c + ".executable", "").trim();
            if (executable.isBlank()) continue;
            commands.add(new BookActionCommand(executable,
                    settings.get(c + ".arguments", ""),
                    settings.get(c + ".workingDirectory", ""),
                    settings.getBoolean(c + ".waitForExit", false)));
        }
        return Optional.of(new BookActionProfile(id, name, settings.getBoolean(base + ".enabled", true), commands));
    }

    private void deleteProfileKeys(String id) {
        String base = PREFIX + id;
        new ArrayList<>(settings.findByPrefix(base + ".").keySet()).forEach(settings::remove);
    }

    private int commandIndex(String key, String base) {
        String prefix = base + ".command.";
        if (!key.startsWith(prefix)) return -1;
        int dot = key.indexOf('.', prefix.length());
        if (dot < 0) return -1;
        try { return Integer.parseInt(key.substring(prefix.length(), dot)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static List<String> splitIds(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) return out;
        Arrays.stream(value.split(","))
                .map(String::trim).filter(BookActionProfileService::validId).distinct().forEach(out::add);
        return out;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,80}");
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        return value.isBlank() ? "Нова дія" : value;
    }

    private static void validate(BookActionProfile profile) {
        if (profile == null || !validId(profile.id())) throw new IllegalArgumentException("Некоректний ID профілю");
        if (profile.name().isBlank()) throw new IllegalArgumentException("Назва профілю порожня");
        if (profile.commands().size() > 64) throw new IllegalArgumentException("Забагато команд у профілі");
        for (BookActionCommand command : profile.commands()) {
            if (command.executable().isBlank()) throw new IllegalArgumentException("Executable не може бути порожнім");
            CommandTemplate.parse(command.arguments()); // syntax validation, no execution
        }
    }
}
