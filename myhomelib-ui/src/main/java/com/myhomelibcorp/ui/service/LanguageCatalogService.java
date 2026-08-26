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

/** File-based, schema-versioned UI and FB2-genre language catalog. */
@Component
@Slf4j
public class LanguageCatalogService {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    private static final List<String> BUNDLED_DEFAULTS = List.of("uk", "en", "bg");
    private static final String AVAILABLE_LANGUAGES_FILE = "available-languages.txt";
    private static final String DIAGNOSTICS_FILE = "language-diagnostics.txt";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path languageDir;
    private final Path availableLanguagesFile;
    private final Path diagnosticsFile;
    private volatile Map<String, Catalog> catalogs = Map.of();
    private volatile List<String> diagnostics = List.of();

    public LanguageCatalogService() {
        this.languageDir = resolveLanguageDirectory();
        this.availableLanguagesFile = AppPaths.configDir().resolve(AVAILABLE_LANGUAGES_FILE);
        this.diagnosticsFile = AppPaths.configDir().resolve(DIAGNOSTICS_FILE);
        initializeFirstRunCatalogues();
        refresh();
    }

    /** Re-scan language files and synchronize generated language/diagnostics files. */
    public synchronized void refresh() {
        List<String> messages = new ArrayList<>();
        Map<String, Catalog> loaded = loadCatalogs(languageDir, messages);
        appendCoverageDiagnostics(loaded, messages);
        catalogs = Collections.unmodifiableMap(loaded);
        diagnostics = List.copyOf(messages);
        writeAvailableLanguagesFile(catalogs);
        writeDiagnosticsFile(diagnostics);
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

    /** Localize a stable FB2 genre code; missing entries safely preserve the DB/catalog label. */
    public String genreName(String language, String genreCode, String fallback) {
        if (genreCode == null || genreCode.isBlank()) return fallback == null ? "" : fallback;
        Catalog catalog = catalogs.get(normalizeCode(language));
        if (catalog == null) catalog = catalogs.get(fallbackLanguage());
        if (catalog == null) return nonBlank(fallback, genreCode);
        return nonBlank(catalog.genres().get(genreCode), nonBlank(fallback, genreCode));
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public Path diagnosticsFile() {
        return diagnosticsFile;
    }

    public boolean hasLanguage(String code) { return catalogs.containsKey(normalizeCode(code)); }
    public String fallbackLanguage() {
        if (catalogs.containsKey("uk")) return "uk";
        return catalogs.keySet().stream().findFirst().orElse("uk");
    }
    public Path languageDirectory() { return languageDir; }
    public Path availableLanguagesFile() { return availableLanguagesFile; }

    private void initializeFirstRunCatalogues() {
        try {
            Files.createDirectories(AppPaths.configDir());
            Files.createDirectories(languageDir);
        } catch (Exception e) {
            log.warn("Cannot create language directories: {}", e.getMessage());
        }
        if (Files.isRegularFile(availableLanguagesFile)) return;
        for (String code : BUNDLED_DEFAULTS) {
            Path target = languageDir.resolve(code + ".json");
            if (Files.isRegularFile(target)) continue;
            String resource = "/lang/default/" + code + ".json";
            try (InputStream in = LanguageCatalogService.class.getResourceAsStream(resource)) {
                if (in == null) { log.warn("Bundled language resource is missing: {}", resource); continue; }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Created default language file {}", target);
            } catch (Exception e) {
                log.warn("Cannot create default language file {}: {}", target, e.getMessage());
            }
        }
    }

    private Map<String, Catalog> loadCatalogs(Path dir, List<String> messages) {
        Map<String, Catalog> result = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return result;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> loadCatalog(path, messages).ifPresent(catalog -> {
                        Catalog previous = result.putIfAbsent(catalog.code(), catalog);
                        if (previous != null) messages.add("ERROR duplicate language code '" + catalog.code() + "' in " + path.getFileName());
                    }));
        } catch (Exception e) {
            messages.add("ERROR cannot scan language directory: " + e.getMessage());
        }
        return result;
    }

    private Optional<Catalog> loadCatalog(Path json, List<String> messages) {
        try {
            Map<String, Object> root = mapper.readValue(Files.readAllBytes(json), new TypeReference<>() {});
            String fileCode = stripExtension(json.getFileName().toString());
            String code = normalizeCode(String.valueOf(root.getOrDefault("code", fileCode)));
            if (code.isBlank()) throw new IllegalArgumentException("invalid language code");
            String name = String.valueOf(root.getOrDefault("name", code)).trim();
            if (name.isBlank()) name = code;

            int schemaVersion = intValue(root.get("schemaVersion"), 1);
            if (schemaVersion < CURRENT_SCHEMA_VERSION) {
                messages.add("WARN " + json.getFileName() + ": schemaVersion=" + schemaVersion
                        + " is legacy; supported with fallback, current=" + CURRENT_SCHEMA_VERSION);
            } else if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported schemaVersion=" + schemaVersion);
            }

            Map<String, String> translations = stringMap(root.get("translations"), true, "translations");
            Map<String, String> genres = stringMap(root.get("genres"), false, "genres");
            return Optional.of(new Catalog(schemaVersion, code, name,
                    Collections.unmodifiableMap(translations), Collections.unmodifiableMap(genres)));
        } catch (Exception e) {
            String message = "ERROR " + json.getFileName() + ": " + e.getMessage();
            messages.add(message);
            log.warn("Ignoring invalid language file {}: {}", json, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, String> stringMap(Object raw, boolean required, String field) {
        if (raw == null && !required) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> rawMap)) throw new IllegalArgumentException(field + " object is " + (required ? "required" : "invalid"));
        Map<String, String> result = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null && value != null && !String.valueOf(key).isBlank()) result.put(String.valueOf(key), String.valueOf(value));
        });
        return result;
    }

    private void appendCoverageDiagnostics(Map<String, Catalog> current, List<String> messages) {
        Catalog reference = current.get("uk");
        if (reference == null && !current.isEmpty()) reference = current.values().iterator().next();
        if (reference == null) { messages.add("ERROR no valid language catalogues found"); return; }
        for (Catalog catalog : current.values()) {
            Set<String> missingUi = new TreeSet<>(reference.translations().keySet());
            missingUi.removeAll(catalog.translations().keySet());
            if (!missingUi.isEmpty()) messages.add("WARN " + catalog.code() + ": missing UI keys=" + missingUi.size()
                    + " sample=" + sample(missingUi));
            Set<String> missingGenres = new TreeSet<>(reference.genres().keySet());
            missingGenres.removeAll(catalog.genres().keySet());
            if (!missingGenres.isEmpty()) messages.add("WARN " + catalog.code() + ": missing genre keys=" + missingGenres.size()
                    + " sample=" + sample(missingGenres));
        }
        if (messages.isEmpty()) messages.add("OK all loaded language catalogues match schema and shipped key coverage");
    }

    private void writeAvailableLanguagesFile(Map<String, Catalog> currentCatalogs) {
        try {
            Files.createDirectories(availableLanguagesFile.getParent());
            StringBuilder text = new StringBuilder()
                    .append("# MyHomeLib available UI languages. UTF-8.\n")
                    .append("# Auto-generated from: ").append(languageDir).append('\n')
                    .append("# Format: language-code=display-name\n")
                    .append("# Add translations as Lang/<code>.json; signing is optional, never mandatory.\n");
            currentCatalogs.values().stream().sorted(Comparator.comparing(Catalog::code))
                    .forEach(c -> text.append(c.code()).append('=').append(c.name()).append('\n'));
            atomicWrite(availableLanguagesFile, text.toString());
        } catch (Exception e) { log.warn("Cannot update available language list {}: {}", availableLanguagesFile, e.getMessage()); }
    }

    private void writeDiagnosticsFile(List<String> messages) {
        try {
            StringBuilder text = new StringBuilder()
                    .append("# MyHomeLib language diagnostics. UTF-8.\n")
                    .append("# Expected schemaVersion=").append(CURRENT_SCHEMA_VERSION).append(". Missing keys use safe fallback.\n");
            messages.forEach(message -> text.append(message).append('\n'));
            atomicWrite(diagnosticsFile, text.toString());
        } catch (Exception e) { log.warn("Cannot write language diagnostics {}: {}", diagnosticsFile, e.getMessage()); }
    }

    private static void atomicWrite(Path target, String text) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static Path resolveLanguageDirectory() {
        String explicit = System.getProperty("myhomelib.langDir");
        if (explicit != null && !explicit.isBlank()) return Paths.get(explicit).toAbsolutePath().normalize();
        Path besideLauncher = AppPaths.launchDir().resolve("Lang").toAbsolutePath().normalize();
        if (Files.isDirectory(besideLauncher)) return besideLauncher;
        if (AppPaths.portableMode()) {
            try { Files.createDirectories(besideLauncher); if (Files.isWritable(besideLauncher)) return besideLauncher; }
            catch (Exception ignored) { }
        }
        return AppPaths.dataDir().resolve("Lang").toAbsolutePath().normalize();
    }

    static String normalizeCode(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.matches("[a-z]{2,3}(-[a-z0-9]{2,8})*") ? normalized : "";
    }
    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    private static int intValue(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String sample(Set<String> values) { return values.stream().limit(8).toList().toString(); }

    private record Catalog(int schemaVersion, String code, String name,
                           Map<String, String> translations, Map<String, String> genres) { }
}
