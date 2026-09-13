package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates and authenticates high-entropy scoped bearer tokens for OPDS/API access.
 * Only SHA-256 token hashes and non-secret metadata are persisted. The raw token is
 * returned exactly once by {@link #create(String, Set)}.
 */
@Component
public class OpdsAccessTokenService {
    private static final String PREFIX = "opds.tokens.";
    private static final String HASH_PREFIX = "sha256$";
    private static final int SECRET_BYTES = 32;
    private static final int ID_BYTES = 9;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApplicationSettingsPort settings;
    private final Clock clock;

    @Autowired
    public OpdsAccessTokenService(ApplicationSettingsPort settings) {
        this(settings, Clock.systemUTC());
    }

    OpdsAccessTokenService(ApplicationSettingsPort settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CreatedToken create(String deviceName, Set<OpdsTokenScope> requestedScopes) {
        EnumSet<OpdsTokenScope> scopes = requestedScopes == null || requestedScopes.isEmpty()
                ? EnumSet.of(OpdsTokenScope.CATALOG_READ)
                : EnumSet.copyOf(requestedScopes);
        String id = randomId();
        String raw = "mhl_" + id + "_" + randomUrl(SECRET_BYTES);
        Instant now = clock.instant();

        Map<String, String> slice = new LinkedHashMap<>(settings.findByPrefix(PREFIX));
        String p = tokenPrefix(id);
        slice.put(p + "hash", hash(raw));
        slice.put(p + "device", cleanDevice(deviceName));
        slice.put(p + "scopes", encodeScopes(scopes));
        slice.put(p + "createdAt", now.toString());
        slice.remove(p + "lastUsedAt");
        slice.remove(p + "revokedAt");
        settings.replaceByPrefix(PREFIX, slice);

        return new CreatedToken(new OpdsAccessTokenInfo(id, cleanDevice(deviceName), scopes, now, null, null), raw);
    }

    public List<OpdsAccessTokenInfo> list() {
        Map<String, String> slice = settings.findByPrefix(PREFIX);
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : slice.entrySet()) {
            String suffix = entry.getKey().substring(PREFIX.length());
            int dot = suffix.indexOf('.');
            if (dot <= 0 || dot == suffix.length() - 1) continue;
            grouped.computeIfAbsent(suffix.substring(0, dot), ignored -> new LinkedHashMap<>())
                    .put(suffix.substring(dot + 1), entry.getValue());
        }
        List<OpdsAccessTokenInfo> result = new ArrayList<>();
        grouped.forEach((id, values) -> toInfo(id, values).ifPresent(result::add));
        result.sort(Comparator.comparing(OpdsAccessTokenInfo::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(result);
    }

    public boolean authenticate(String rawToken, OpdsTokenScope requiredScope) {
        return authorize(rawToken, requiredScope) == Authorization.AUTHORIZED;
    }

    public Authorization authorize(String rawToken, OpdsTokenScope requiredScope) {
        ParsedToken parsed = parse(rawToken);
        if (parsed == null || requiredScope == null) return Authorization.INVALID;
        String p = tokenPrefix(parsed.id());
        Map<String, String> slice = settings.findByPrefix(p);
        String storedHash = slice.get(p + "hash");
        if (storedHash == null || !slice.getOrDefault(p + "revokedAt", "").isBlank()) return Authorization.INVALID;
        if (!constantTimeHashMatches(rawToken, storedHash)) return Authorization.INVALID;
        Set<OpdsTokenScope> scopes = decodeScopes(slice.get(p + "scopes"));
        if (!scopes.contains(requiredScope)) return Authorization.INSUFFICIENT_SCOPE;
        settings.put(p + "lastUsedAt", clock.instant().toString());
        return Authorization.AUTHORIZED;
    }

    public boolean revoke(String tokenId) {
        String id = cleanId(tokenId);
        if (id.isBlank()) return false;
        String p = tokenPrefix(id);
        Map<String, String> slice = settings.findByPrefix(p);
        if (!slice.containsKey(p + "hash")) return false;
        settings.put(p + "revokedAt", clock.instant().toString());
        return true;
    }

    private java.util.Optional<OpdsAccessTokenInfo> toInfo(String id, Map<String, String> values) {
        if (!values.containsKey("hash")) return java.util.Optional.empty();
        return java.util.Optional.of(new OpdsAccessTokenInfo(
                id,
                values.getOrDefault("device", ""),
                decodeScopes(values.get("scopes")),
                instant(values.get("createdAt")),
                instant(values.get("lastUsedAt")),
                instant(values.get("revokedAt"))));
    }

    private static String tokenPrefix(String id) { return PREFIX + id + "."; }


    private static String randomId() {
        byte[] value = new byte[ID_BYTES];
        RANDOM.nextBytes(value);
        return java.util.HexFormat.of().formatHex(value);
    }

    private static String randomUrl(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean constantTimeHashMatches(String raw, String stored) {
        if (stored == null || !stored.startsWith(HASH_PREFIX)) return false;
        byte[] left = hash(raw).getBytes(StandardCharsets.US_ASCII);
        byte[] right = stored.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(left, right);
    }

    private static ParsedToken parse(String rawToken) {
        if (rawToken == null || rawToken.length() > 256 || !rawToken.startsWith("mhl_")) return null;
        int separator = rawToken.indexOf('_', 4);
        if (separator <= 4 || separator >= rawToken.length() - 1) return null;
        String id = cleanId(rawToken.substring(4, separator));
        if (id.isBlank()) return null;
        return new ParsedToken(id);
    }

    private static String cleanId(String value) {
        if (value == null || value.length() > 64) return "";
        return value.matches("[A-Za-z0-9_-]+") ? value : "";
    }

    private static String cleanDevice(String value) {
        if (value == null) return "";
        String cleaned = value.strip().replaceAll("[\\p{Cntrl}]", "");
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120);
    }

    private static String encodeScopes(Set<OpdsTokenScope> scopes) {
        return scopes.stream().sorted().map(Enum::name).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static Set<OpdsTokenScope> decodeScopes(String encoded) {
        if (encoded == null || encoded.isBlank()) return Set.of();
        EnumSet<OpdsTokenScope> result = EnumSet.noneOf(OpdsTokenScope.class);
        for (String value : encoded.split(",")) {
            try { result.add(OpdsTokenScope.valueOf(value.trim().toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) { }
        }
        return Set.copyOf(result);
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    public enum Authorization { AUTHORIZED, INVALID, INSUFFICIENT_SCOPE }

    public record CreatedToken(OpdsAccessTokenInfo info, String token) {
        public CreatedToken {
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(token, "token");
        }
    }

    private record ParsedToken(String id) { }
}
