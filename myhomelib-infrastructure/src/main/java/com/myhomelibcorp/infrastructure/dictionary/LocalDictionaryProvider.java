package com.myhomelibcorp.infrastructure.dictionary;

import com.myhomelibcorp.application.dictionary.DictionaryEntry;
import com.myhomelibcorp.application.dictionary.DictionaryProvider;
import com.myhomelibcorp.application.dictionary.DictionaryQuery;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Offline UTF-8 TSV dictionary. A user file can override/extend the bundled starter dictionary.
 * Format: language TAB headword TAB part-of-speech TAB definition TAB examples separated by " | ".
 */
@Component
public final class LocalDictionaryProvider implements DictionaryProvider {
    public static final String PROVIDER_ID = "local-dictionary";
    public static final String PROVIDER_NAME = "Local dictionary";
    private static final String DEFAULT_RESOURCE = "/dictionary/default.tsv";
    private static final int MAX_SCAN_LINES = 500_000;

    private final ApplicationSettingsPort settings;

    public LocalDictionaryProvider(ApplicationSettingsPort settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override public String id() { return PROVIDER_ID; }
    @Override public String displayName() { return PROVIDER_NAME; }
    @Override public boolean isOffline() { return true; }
    @Override public boolean isEnabled() { return settings.getBoolean("dictionary.local.enabled", true); }

    @Override
    public List<DictionaryEntry> lookup(DictionaryQuery query, TextProviderRequestContext context)
            throws TextProviderException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(context, "context");
        context.throwIfStopped();

        List<DictionaryEntry> result = new ArrayList<>();
        Path custom = customPath();
        if (custom != null && Files.isRegularFile(custom)) {
            scanFile(custom, query, context, result);
        }
        if (result.size() < query.limit()) {
            scanBundled(query, context, result);
        }
        return List.copyOf(result.stream().distinct().limit(query.limit()).toList());
    }

    private Path customPath() {
        String configured = settings.get("dictionary.local.path", "");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return AppPaths.configDir().resolve("dictionary.tsv").toAbsolutePath().normalize();
    }

    private void scanFile(
            Path file,
            DictionaryQuery query,
            TextProviderRequestContext context,
            List<DictionaryEntry> result) throws TextProviderException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            scan(reader, file.toString(), query, context, result);
        } catch (IOException error) {
            throw new TextProviderException(
                    TextProviderErrorKind.UNAVAILABLE,
                    "Local dictionary cannot be read",
                    error);
        }
    }

    private void scanBundled(
            DictionaryQuery query,
            TextProviderRequestContext context,
            List<DictionaryEntry> result) throws TextProviderException {
        try (InputStream input = LocalDictionaryProvider.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                scan(reader, "builtin", query, context, result);
            }
        } catch (IOException error) {
            throw new TextProviderException(
                    TextProviderErrorKind.UNAVAILABLE,
                    "Bundled dictionary cannot be read",
                    error);
        }
    }

    private void scan(
            BufferedReader reader,
            String source,
            DictionaryQuery query,
            TextProviderRequestContext context,
            List<DictionaryEntry> result) throws IOException, TextProviderException {
        String wanted = normalize(query.term());
        String wantedLanguage = query.language();
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null && lineNumber < MAX_SCAN_LINES && result.size() < query.limit()) {
            lineNumber++;
            if ((lineNumber & 127) == 0) context.throwIfStopped();
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length < 4) continue;
            String language = normalizeLanguage(fields[0]);
            String headword = fields[1].trim();
            if (!wantedLanguage.isBlank() && !languageMatches(wantedLanguage, language)) continue;
            if (!normalize(headword).equals(wanted)) continue;
            String definition = fields[3].trim();
            if (definition.isBlank()) continue;
            List<String> examples = fields.length < 5 || fields[4].isBlank()
                    ? List.of()
                    : Arrays.stream(fields[4].split("\\s*\\|\\s*"))
                            .map(String::trim).filter(value -> !value.isBlank()).limit(20).toList();
            result.add(new DictionaryEntry(
                    PROVIDER_ID, PROVIDER_NAME, headword, language, fields[2], definition, examples, source));
        }
        context.throwIfStopped();
    }

    private static boolean languageMatches(String wanted, String actual) {
        if (wanted.equals(actual)) return true;
        int dash = wanted.indexOf('-');
        String wantedBase = dash < 0 ? wanted : wanted.substring(0, dash);
        int actualDash = actual.indexOf('-');
        String actualBase = actualDash < 0 ? actual : actual.substring(0, actualDash);
        return !wantedBase.isBlank() && wantedBase.equals(actualBase);
    }

    private static String normalizeLanguage(String value) {
        return value == null ? "" : value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("\\s+", " ");
    }
}
