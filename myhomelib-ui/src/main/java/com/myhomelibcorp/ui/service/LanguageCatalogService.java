package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.shared.util.AtomicFileSupport;

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
    private static final long MAX_LANGUAGE_FILE_BYTES = 4L * 1024 * 1024;
    public static final int CURRENT_SCHEMA_VERSION = 3;
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

    /**
     * Localized FB2 genre labels are authoritative in Lang/&lt;language&gt;.json.
     * Stable textual FB2 codes are the identity. Legacy numeric ids are compatibility
     * aliases only. If an exact extended genre cannot be resolved, the localized parent
     * group is used as a safe fallback; raw internal codes are never rendered.
     */
    public String genreName(String language, String genreCode, String fallback) {
        if (genreCode == null || genreCode.isBlank()) return "";
        String requestedCode = genreCode.trim();
        String sourceLabel = fallback == null ? "" : fallback.trim();

        Catalog primary = catalogFor(language);
        Catalog fallbackCatalog = catalogs.get(fallbackLanguage());
        String canonicalCode = canonicalGenreCode(primary, fallbackCatalog, requestedCode);

        String exact = firstNonBlank(
                genreLabel(primary, canonicalCode),
                genreLabel(fallbackCatalog, canonicalCode));
        if (!exact.isBlank()) return exact;

        String parentKey = firstNonBlank(
                genreParent(primary, canonicalCode),
                genreParent(fallbackCatalog, canonicalCode));
        if (parentKey.isBlank() && isNumericGenreCode(requestedCode)) {
            String baseCode = numericBaseCode(requestedCode);
            parentKey = firstNonBlank(
                    legacyBaseParent(primary, baseCode),
                    legacyBaseParent(fallbackCatalog, baseCode));
        }
        String parentLabel = firstNonBlank(
                genreGroupLabel(primary, parentKey),
                genreGroupLabel(fallbackCatalog, parentKey));
        if (!parentLabel.isBlank()) return parentLabel;

        // A genuinely custom source genre may still have a meaningful human label.
        // Never leak an internal identifier such as sf_fantasy/det_classic into the UI.
        if (!sourceLabel.isBlank()
                && !sourceLabel.equalsIgnoreCase(requestedCode)
                && !sourceLabel.equalsIgnoreCase(canonicalCode)
                && !looksLikeInternalGenreCode(sourceLabel)) {
            return sourceLabel;
        }
        return "";
    }

    /**
     * Returns false for a base/numeric fallback when the same book/result set already
     * contains a more specific extended genre from the same parent group.
     */
    public boolean shouldDisplayGenre(String language, String genreCode, Collection<String> siblingCodes) {
        if (genreCode == null || genreCode.isBlank()) return false;
        Catalog primary = catalogFor(language);
        Catalog fallbackCatalog = catalogs.get(fallbackLanguage());
        String requestedCode = genreCode.trim();
        String canonicalCode = canonicalGenreCode(primary, fallbackCatalog, requestedCode);
        if (hasExactGenre(primary, canonicalCode) || hasExactGenre(fallbackCatalog, canonicalCode)) return true;

        String group = genreGroupKey(primary, fallbackCatalog, requestedCode, canonicalCode);
        if (group.isBlank() || siblingCodes == null || siblingCodes.isEmpty()) return true;
        for (String sibling : siblingCodes) {
            if (sibling == null || sibling.isBlank() || sibling.equals(requestedCode)) continue;
            String siblingCanonical = canonicalGenreCode(primary, fallbackCatalog, sibling.trim());
            if (!(hasExactGenre(primary, siblingCanonical) || hasExactGenre(fallbackCatalog, siblingCanonical))) continue;
            String siblingGroup = genreGroupKey(primary, fallbackCatalog, sibling.trim(), siblingCanonical);
            if (group.equals(siblingGroup)) return false;
        }
        return true;
    }

    private Catalog catalogFor(String language) {
        Catalog catalog = catalogs.get(normalizeCode(language));
        return catalog != null ? catalog : catalogs.get(fallbackLanguage());
    }

    private static String canonicalGenreCode(Catalog primary, Catalog fallback, String requestedCode) {
        if (primary != null) {
            String mapped = primary.genreAliases().get(requestedCode);
            if (mapped != null && !mapped.isBlank()) return mapped.trim();
        }
        if (fallback != null) {
            String mapped = fallback.genreAliases().get(requestedCode);
            if (mapped != null && !mapped.isBlank()) return mapped.trim();
        }
        return requestedCode;
    }

    private static String genreLabel(Catalog catalog, String canonicalCode) {
        if (catalog == null || canonicalCode == null || canonicalCode.isBlank()) return "";
        return nonBlank(catalog.genres().get(canonicalCode), "").trim();
    }

    private static boolean hasExactGenre(Catalog catalog, String canonicalCode) {
        return catalog != null && canonicalCode != null && catalog.genres().containsKey(canonicalCode)
                && !nonBlank(catalog.genres().get(canonicalCode), "").isBlank();
    }

    private static String genreParent(Catalog catalog, String canonicalCode) {
        if (catalog == null || canonicalCode == null || canonicalCode.isBlank()) return "";
        return nonBlank(catalog.genreParents().get(canonicalCode), "").trim();
    }

    private static String legacyBaseParent(Catalog catalog, String baseCode) {
        if (catalog == null || baseCode == null || baseCode.isBlank()) return "";
        return nonBlank(catalog.legacyBaseAliases().get(baseCode), "").trim();
    }

    private static String genreGroupLabel(Catalog catalog, String parentKey) {
        if (catalog == null || parentKey == null || parentKey.isBlank()) return "";
        return nonBlank(catalog.genreGroups().get(parentKey), "").trim();
    }

    private static String genreGroupKey(Catalog primary, Catalog fallback,
                                        String requestedCode, String canonicalCode) {
        String group = firstNonBlank(genreParent(primary, canonicalCode), genreParent(fallback, canonicalCode));
        if (!group.isBlank()) return group;
        if (!isNumericGenreCode(requestedCode)) return "";
        String baseCode = numericBaseCode(requestedCode);
        return firstNonBlank(legacyBaseParent(primary, baseCode), legacyBaseParent(fallback, baseCode));
    }

    private static boolean isNumericGenreCode(String code) {
        return code != null && code.matches("0\\.\\d+(?:\\.\\d+)?");
    }

    private static String numericBaseCode(String code) {
        if (!isNumericGenreCode(code)) return "";
        int secondDot = code.indexOf('.', 2);
        return secondDot < 0 ? code : code.substring(0, secondDot);
    }

    private static boolean looksLikeInternalGenreCode(String value) {
        return value.matches("[a-z][a-z0-9_]{1,80}") || isNumericGenreCode(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
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
            if (Files.size(json) > MAX_LANGUAGE_FILE_BYTES) throw new IllegalArgumentException("language file is too large");
            Map<String, Object> root = mapper.readValue(json.toFile(), new TypeReference<>() {});
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
            Map<String, String> genreAliases = stringMap(root.get("genreAliases"), false, "genreAliases");
            Map<String, String> genreGroups = stringMap(root.get("genreGroups"), false, "genreGroups");
            Map<String, String> genreParents = stringMap(root.get("genreParents"), false, "genreParents");
            Map<String, String> legacyBaseAliases = stringMap(root.get("legacyBaseAliases"), false, "legacyBaseAliases");
            for (Map.Entry<String, String> alias : genreAliases.entrySet()) {
                if (!genres.containsKey(alias.getValue())) {
                    throw new IllegalArgumentException("genreAliases target is missing from genres: "
                            + alias.getKey() + " -> " + alias.getValue());
                }
            }
            for (Map.Entry<String, String> parent : genreParents.entrySet()) {
                if (!genreGroups.containsKey(parent.getValue())) {
                    throw new IllegalArgumentException("genreParents target is missing from genreGroups: "
                            + parent.getKey() + " -> " + parent.getValue());
                }
            }
            for (Map.Entry<String, String> base : legacyBaseAliases.entrySet()) {
                if (!genreGroups.containsKey(base.getValue())) {
                    throw new IllegalArgumentException("legacyBaseAliases target is missing from genreGroups: "
                            + base.getKey() + " -> " + base.getValue());
                }
            }
            return Optional.of(new Catalog(schemaVersion, code, name,
                    Collections.unmodifiableMap(translations), Collections.unmodifiableMap(genres),
                    Collections.unmodifiableMap(genreAliases), Collections.unmodifiableMap(genreGroups),
                    Collections.unmodifiableMap(genreParents), Collections.unmodifiableMap(legacyBaseAliases)));
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
            Set<String> missingAliases = new TreeSet<>(reference.genreAliases().keySet());
            missingAliases.removeAll(catalog.genreAliases().keySet());
            if (!missingAliases.isEmpty()) messages.add("WARN " + catalog.code() + ": missing genre aliases=" + missingAliases.size()
                    + " sample=" + sample(missingAliases));
            Set<String> missingGroups = new TreeSet<>(reference.genreGroups().keySet());
            missingGroups.removeAll(catalog.genreGroups().keySet());
            if (!missingGroups.isEmpty()) messages.add("WARN " + catalog.code() + ": missing genre groups=" + missingGroups.size()
                    + " sample=" + sample(missingGroups));
            Set<String> missingParents = new TreeSet<>(reference.genreParents().keySet());
            missingParents.removeAll(catalog.genreParents().keySet());
            if (!missingParents.isEmpty()) messages.add("WARN " + catalog.code() + ": missing genre parents=" + missingParents.size()
                    + " sample=" + sample(missingParents));
            Set<String> missingBaseAliases = new TreeSet<>(reference.legacyBaseAliases().keySet());
            missingBaseAliases.removeAll(catalog.legacyBaseAliases().keySet());
            if (!missingBaseAliases.isEmpty()) messages.add("WARN " + catalog.code() + ": missing legacy base aliases=" + missingBaseAliases.size()
                    + " sample=" + sample(missingBaseAliases));
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
        AtomicFileSupport.moveReplacing(temp, target);
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
                           Map<String, String> translations, Map<String, String> genres,
                           Map<String, String> genreAliases, Map<String, String> genreGroups,
                           Map<String, String> genreParents, Map<String, String> legacyBaseAliases) { }
}
