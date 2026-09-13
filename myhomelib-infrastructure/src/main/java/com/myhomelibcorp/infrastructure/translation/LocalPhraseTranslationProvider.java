package com.myhomelibcorp.infrastructure.translation;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.application.translation.TranslationProvider;
import com.myhomelibcorp.application.translation.TranslationQuery;
import com.myhomelibcorp.application.translation.TranslationResult;
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
import java.util.Locale;
import java.util.Objects;

/** Offline exact-phrase UTF-8 TSV translation provider. */
@Component
public final class LocalPhraseTranslationProvider implements TranslationProvider {
    public static final String PROVIDER_ID = "local-phrases";
    public static final String PROVIDER_NAME = "Local phrase dictionary";
    private static final String DEFAULT_RESOURCE = "/translation/default.tsv";
    private static final int MAX_SCAN_LINES = 500_000;

    private final ApplicationSettingsPort settings;

    public LocalPhraseTranslationProvider(ApplicationSettingsPort settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override public String id() { return PROVIDER_ID; }
    @Override public String displayName() { return PROVIDER_NAME; }
    @Override public boolean isRemote() { return false; }
    @Override public boolean isEnabled() { return settings.getBoolean("translation.local.enabled", true); }

    @Override
    public TranslationResult translate(TranslationQuery query, TextProviderRequestContext context)
            throws TextProviderException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(context, "context");
        context.throwIfStopped();

        Path custom = customPath();
        if (custom != null && Files.isRegularFile(custom)) {
            TranslationResult result = scanFile(custom, query, context);
            if (result != null) return result;
        }
        TranslationResult bundled = scanBundled(query, context);
        if (bundled != null) return bundled;
        throw new TextProviderException(TextProviderErrorKind.NOT_FOUND, "No local translation found");
    }

    private Path customPath() {
        String configured = settings.get("translation.local.path", "");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return AppPaths.configDir().resolve("translations.tsv").toAbsolutePath().normalize();
    }

    private TranslationResult scanFile(Path file, TranslationQuery query, TextProviderRequestContext context)
            throws TextProviderException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return scan(reader, query, context);
        } catch (IOException error) {
            throw new TextProviderException(TextProviderErrorKind.UNAVAILABLE, "Local translations cannot be read", error);
        }
    }

    private TranslationResult scanBundled(TranslationQuery query, TextProviderRequestContext context)
            throws TextProviderException {
        try (InputStream input = LocalPhraseTranslationProvider.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return scan(reader, query, context);
            }
        } catch (IOException error) {
            throw new TextProviderException(TextProviderErrorKind.UNAVAILABLE, "Bundled translations cannot be read", error);
        }
    }

    private TranslationResult scan(BufferedReader reader, TranslationQuery query, TextProviderRequestContext context)
            throws IOException, TextProviderException {
        String wanted = normalize(query.text());
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null && lineNumber < MAX_SCAN_LINES) {
            lineNumber++;
            if ((lineNumber & 127) == 0) context.throwIfStopped();
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length < 4) continue;
            String source = language(fields[0]);
            String target = language(fields[1]);
            if (!query.targetLanguage().equals(target)) continue;
            if (!query.sourceLanguage().isBlank() && !query.sourceLanguage().equals(source)) continue;
            if (!normalize(fields[2]).equals(wanted)) continue;
            String translated = fields[3].trim();
            if (translated.isBlank()) continue;
            context.throwIfStopped();
            return new TranslationResult(PROVIDER_ID, PROVIDER_NAME, source, target, translated);
        }
        context.throwIfStopped();
        return null;
    }

    private static String language(String value) {
        return value == null ? "" : value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
