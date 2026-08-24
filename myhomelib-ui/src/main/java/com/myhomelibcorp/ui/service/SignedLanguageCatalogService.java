package com.myhomelibcorp.ui.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * Loads optional external UI catalogues from {@code Lang/<code>.json}.
 * A detached Ed25519 signature in {@code <code>.json.sig} is mandatory so an
 * arbitrary file beside the executable cannot silently replace trusted UI text.
 */
@Component
@Slf4j
public class SignedLanguageCatalogService {
    // Project language-catalog public key. The corresponding private key is not distributed.
    private static final String PUBLIC_KEY_B64 =
            "MCowBQYDK2VwAyEA7envlmAXETXMsmVNUYEp186T22TwDwSbg+mlZUX2u4E=";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Catalog> catalogs;

    public SignedLanguageCatalogService() {
        this.catalogs = Collections.unmodifiableMap(loadCatalogs());
    }

    public Map<String, String> availableLanguages() {
        Map<String, String> result = new LinkedHashMap<>();
        catalogs.values().stream().sorted(Comparator.comparing(Catalog::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(c -> result.put(c.code(), c.name()));
        return result;
    }

    public Optional<Map<String, String>> translations(String code) {
        Catalog c = catalogs.get(normalizeCode(code));
        return c == null ? Optional.empty() : Optional.of(c.translations());
    }

    public boolean hasLanguage(String code) { return catalogs.containsKey(normalizeCode(code)); }

    private Map<String, Catalog> loadCatalogs() {
        Map<String, Catalog> result = new LinkedHashMap<>();
        Path dir = AppPaths.launchDir().resolve("Lang");
        if (!Files.isDirectory(dir)) return result;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path json : ds) {
                try {
                    Path sig = json.resolveSibling(json.getFileName() + ".sig");
                    if (!Files.isRegularFile(sig)) {
                        log.warn("Ignoring unsigned language catalogue {}", json);
                        continue;
                    }
                    byte[] payload = Files.readAllBytes(json);
                    if (!verify(payload, Files.readString(sig, StandardCharsets.US_ASCII).trim())) {
                        log.warn("Ignoring language catalogue with invalid signature {}", json);
                        continue;
                    }
                    Map<String, Object> root = mapper.readValue(payload, new TypeReference<>() {});
                    String code = normalizeCode(String.valueOf(root.getOrDefault("code", stripExt(json.getFileName().toString()))));
                    String name = String.valueOf(root.getOrDefault("name", code));
                    if (code.isBlank() || code.equals("uk") || code.equals("en")) {
                        log.warn("Ignoring reserved/invalid external language code {}", code);
                        continue;
                    }
                    Object raw = root.get("translations");
                    if (!(raw instanceof Map<?, ?> m)) throw new IllegalArgumentException("translations object is required");
                    Map<String, String> translations = new LinkedHashMap<>();
                    m.forEach((k, v) -> { if (k != null && v != null) translations.put(String.valueOf(k), String.valueOf(v)); });
                    if (translations.isEmpty()) throw new IllegalArgumentException("translations is empty");
                    result.put(code, new Catalog(code, name, Collections.unmodifiableMap(translations)));
                    log.info("Loaded signed language catalogue {} ({})", name, code);
                } catch (Exception e) {
                    log.warn("Cannot load external language catalogue {}: {}", json, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Cannot scan language directory {}", dir, e);
        }
        return result;
    }

    private static boolean verify(byte[] payload, String base64Signature) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(PUBLIC_KEY_B64);
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(payload);
        return verifier.verify(Base64.getDecoder().decode(base64Signature.replaceAll("\\s+", "")));
    }

    private static String normalizeCode(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return x.matches("[a-z]{2,3}(-[a-z0-9]{2,8})?") ? x : "";
    }
    private static String stripExt(String s) { int i = s.lastIndexOf('.'); return i > 0 ? s.substring(0, i) : s; }

    private record Catalog(String code, String name, Map<String, String> translations) { }
}
