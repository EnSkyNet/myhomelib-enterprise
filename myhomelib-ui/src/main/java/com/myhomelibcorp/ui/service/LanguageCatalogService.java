package com.myhomelibcorp.ui.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * File-based UI language catalog.
 *
 * <p>Translations live in standalone UTF-8 JSON files in {@code Lang/}. On the
 * first run the bundled default catalogues are copied there. Every refresh scans
 * the directory again and synchronizes {@code config/available-languages.txt},
 * so adding another {@code *.json} file makes the language discoverable without
 * changing Java code.</p>
 */
@Component
@Slf4j
public class LanguageCatalogService {
    private static final List<String> BUNDLED_DEFAULTS = List.of("uk", "en", "bg");
    private static final String AVAILABLE_LANGUAGES_FILE = "available-languages.txt";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path languageDir;
    private final Path availableLanguagesFile;
    private volatile Map<String, Catalog> catalogs = Map.of();

    public LanguageCatalogService() {
        this.languageDir = resolveLanguageDirectory();
        this.availableLanguagesFile = AppPaths.configDir().resolve(AVAILABLE_LANGUAGES_FILE);
        initializeFirstRunCatalogues();
        refresh();
    }

    /** Re-scan language files and synchronize the generated language list. */
    public synchronized void refresh() {
        Map<String, Catalog> loaded = loadCatalogs(languageDir);
        catalogs = Collections.unmodifiableMap(loaded);
        writeAvailableLanguagesFile(catalogs);
    }

    public Map<String, String> availableLanguages() {
        Map<String, String> result = new LinkedHashMap<>();
        catalogs.values().stream()
                .sorted(Comparator.comparing(Catalog::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(c -> result.put(c.code(), c.name()));
        return Collections.unmodifiableMap(result);
    }

    public Optional<Map<String, String>> translations(String code) {
        Catalog catalog = catalogs.get(normalizeCode(code));
        return catalog == null ? Optional.empty() : Optional.of(catalog.translations());
    }

    public boolean hasLanguage(String code) {
        return catalogs.containsKey(normalizeCode(code));
    }

    public String fallbackLanguage() {
        if (catalogs.containsKey("uk")) return "uk";
        return catalogs.keySet().stream().findFirst().orElse("uk");
    }

    public Path languageDirectory() {
        return languageDir;
    }

    public Path availableLanguagesFile() {
        return availableLanguagesFile;
    }

    private void initializeFirstRunCatalogues() {
        try {
            Files.createDirectories(AppPaths.configDir());
            Files.createDirectories(languageDir);
        } catch (Exception e) {
            log.warn("Cannot create language directories: {}", e.getMessage());
        }

        // available-languages.txt is the first-run marker. Do not silently restore
        // languages that the user intentionally removes after initialization.
        if (Files.isRegularFile(availableLanguagesFile)) return;

        for (String code : BUNDLED_DEFAULTS) {
            Path target = languageDir.resolve(code + ".json");
            if (Files.isRegularFile(target)) continue;
            String resource = "/lang/default/" + code + ".json";
            try (InputStream in = LanguageCatalogService.class.getResourceAsStream(resource)) {
                if (in == null) {
                    log.warn("Bundled language resource is missing: {}", resource);
                    continue;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Created default language file {}", target);
            } catch (Exception e) {
                log.warn("Cannot create default language file {}: {}", target, e.getMessage());
            }
        }
    }

    private Map<String, Catalog> loadCatalogs(Path dir) {
        Map<String, Catalog> result = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return result;

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> loadCatalog(path).ifPresent(catalog -> {
                        Catalog previous = result.putIfAbsent(catalog.code(), catalog);
                        if (previous != null) {
                            log.warn("Ignoring duplicate language code {} in {}", catalog.code(), path);
                        }
                    }));
        } catch (Exception e) {
            log.warn("Cannot scan language directory {}: {}", dir, e.getMessage());
        }
        return result;
    }

    private Optional<Catalog> loadCatalog(Path json) {
        try {
            byte[] payload = Files.readAllBytes(json);
            Map<String, Object> root = mapper.readValue(payload, new TypeReference<>() {});
            String fileCode = stripExtension(json.getFileName().toString());
            String code = normalizeCode(String.valueOf(root.getOrDefault("code", fileCode)));
            if (code.isBlank()) throw new IllegalArgumentException("invalid language code");

            String name = String.valueOf(root.getOrDefault("name", code)).trim();
            if (name.isBlank()) name = code;

            Object rawTranslations = root.get("translations");
            if (!(rawTranslations instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("translations object is required");
            }
            Map<String, String> translations = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    translations.put(String.valueOf(key), String.valueOf(value));
                }
            });

            return Optional.of(new Catalog(code, name, Collections.unmodifiableMap(translations)));
        } catch (Exception e) {
            log.warn("Ignoring invalid language file {}: {}", json, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeAvailableLanguagesFile(Map<String, Catalog> currentCatalogs) {
        try {
            Files.createDirectories(availableLanguagesFile.getParent());
            StringBuilder text = new StringBuilder()
                    .append("# MyHomeLib available UI languages. UTF-8.\n")
                    .append("# Auto-generated from: ").append(languageDir).append('\n')
                    .append("# Format: language-code=display-name\n")
                    .append("# This file is synchronized automatically; add translations as Lang/<code>.json.\n");

            currentCatalogs.values().stream()
                    .sorted(Comparator.comparing(Catalog::code))
                    .forEach(c -> text.append(c.code()).append('=').append(c.name()).append('\n'));

            Path temp = availableLanguagesFile.resolveSibling(availableLanguagesFile.getFileName() + ".tmp");
            Files.writeString(temp, text.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, availableLanguagesFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, availableLanguagesFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Cannot update available language list {}: {}", availableLanguagesFile, e.getMessage());
        }
    }

    private static Path resolveLanguageDirectory() {
        String explicit = System.getProperty("myhomelib.langDir");
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }

        Path besideLauncher = AppPaths.launchDir().resolve("Lang").toAbsolutePath().normalize();
        if (Files.isDirectory(besideLauncher)) return besideLauncher;

        // Portable installations keep editable language files beside the launcher.
        if (AppPaths.portableMode()) {
            try {
                Files.createDirectories(besideLauncher);
                if (Files.isWritable(besideLauncher)) return besideLauncher;
            } catch (Exception ignored) {
                // Fall through to the user data directory.
            }
        }

        // Normal installed applications should not create Lang in an arbitrary
        // process working directory; use the stable writable data directory.
        return AppPaths.dataDir().resolve("Lang").toAbsolutePath().normalize();
    }

    static String normalizeCode(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.matches("[a-z]{2,3}(-[a-z0-9]{2,8})*") ? normalized : "";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private record Catalog(String code, String name, Map<String, String> translations) { }
}
