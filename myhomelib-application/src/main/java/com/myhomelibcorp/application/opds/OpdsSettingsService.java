package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OpdsSettingsService {
    private static final String P = "opds.";
    private static final String PASSWORD = P + "password";
    private static final String TLS_KEYSTORE_PASSWORD = P + "tls.keyStorePassword";
    private final ApplicationSettingsPort settings;

    public OpdsServerSettings load() {
        migrateLegacyPasswordIfNeeded();
        migrateLegacyTlsPasswordIfNeeded();
        var defaults = OpdsServerSettings.defaults();
        var defaultTls = defaults.tls();
        var defaultLimits = defaults.limits();
        return new OpdsServerSettings(
                settings.get(P + "bindAddress", defaults.bindAddress()),
                settings.getInt(P + "port", defaults.port()),
                settings.getBoolean(P + "basicAuthEnabled", false),
                settings.get(P + "username", ""),
                settings.get(PASSWORD, ""),
                settings.getBoolean(P + "autostart", false),
                new OpdsTlsSettings(
                        settings.getBoolean(P + "tls.enabled", defaultTls.enabled()),
                        settings.get(P + "tls.keyStorePath", defaultTls.keyStorePath()),
                        settings.get(P + "tls.keyStoreType", defaultTls.keyStoreType()),
                        loadTlsPassword()),
                new OpdsSecurityLimits(
                        settings.getInt(P + "limits.maxConcurrentRequests", defaultLimits.maxConcurrentRequests()),
                        settings.getInt(P + "limits.listenBacklog", defaultLimits.listenBacklog()),
                        settings.getInt(P + "limits.authFailuresPerWindow", defaultLimits.authFailuresPerWindow()),
                        settings.getInt(P + "limits.authWindowSeconds", defaultLimits.authWindowSeconds()),
                        settings.getInt(P + "limits.authBlockSeconds", defaultLimits.authBlockSeconds()),
                        settings.getBoolean(P + "limits.healthRequiresAuthWhenExposed",
                                defaultLimits.healthRequiresAuthWhenExposed())));
    }

    /**
     * Persists the complete OPDS namespace through one replace operation. When Basic Auth is
     * enabled and password is blank, an already configured hash is retained so the UI can leave
     * the password field empty without exposing the stored credential representation.
     *
     * <p>The TLS keystore password is persisted only through the application encryption envelope;
     * plaintext is never written to application settings.</p>
     */
    public void save(OpdsServerSettings value) {
        String password = normalizePasswordForStorage(value);
        Map<String, String> replacement = new LinkedHashMap<>();
        replacement.put(P + "bindAddress", value.bindAddress());
        replacement.put(P + "port", Integer.toString(value.port()));
        replacement.put(P + "basicAuthEnabled", Boolean.toString(value.basicAuthEnabled()));
        replacement.put(P + "username", value.username());
        replacement.put(PASSWORD, password);
        replacement.put(P + "autostart", Boolean.toString(value.autostart()));

        OpdsTlsSettings tls = value.tls();
        replacement.put(P + "tls.enabled", Boolean.toString(tls.enabled()));
        replacement.put(P + "tls.keyStorePath", tls.keyStorePath());
        replacement.put(P + "tls.keyStoreType", tls.keyStoreType());
        replacement.put(TLS_KEYSTORE_PASSWORD, normalizeTlsPasswordForStorage(tls));

        OpdsSecurityLimits limits = value.limits();
        replacement.put(P + "limits.maxConcurrentRequests", Integer.toString(limits.maxConcurrentRequests()));
        replacement.put(P + "limits.listenBacklog", Integer.toString(limits.listenBacklog()));
        replacement.put(P + "limits.authFailuresPerWindow", Integer.toString(limits.authFailuresPerWindow()));
        replacement.put(P + "limits.authWindowSeconds", Integer.toString(limits.authWindowSeconds()));
        replacement.put(P + "limits.authBlockSeconds", Integer.toString(limits.authBlockSeconds()));
        replacement.put(P + "limits.healthRequiresAuthWhenExposed",
                Boolean.toString(limits.healthRequiresAuthWhenExposed()));
        settings.replaceByPrefix(P, replacement);
    }

    public boolean hasStoredPassword() {
        migrateLegacyPasswordIfNeeded();
        return !settings.get(PASSWORD, "").isBlank();
    }

    private String normalizePasswordForStorage(OpdsServerSettings value) {
        if (!value.basicAuthEnabled()) return "";
        String incoming = value.password() == null ? "" : value.password();
        if (OpdsPasswordHash.isHash(incoming)) return incoming;
        if (!incoming.isBlank()) return OpdsPasswordHash.hash(incoming);
        String current = settings.get(PASSWORD, "");
        if (current.isBlank()) return "";
        return OpdsPasswordHash.isHash(current) ? current : OpdsPasswordHash.hash(current);
    }


    private String normalizeTlsPasswordForStorage(OpdsTlsSettings tls) {
        String incoming = tls.keyStorePassword() == null ? "" : tls.keyStorePassword();
        if (!incoming.isBlank()) {
            return EncryptionUtil.encrypt(incoming);
        }
        String currentPath = settings.get(P + "tls.keyStorePath", "");
        if (!currentPath.equals(tls.keyStorePath())) return "";
        String current = settings.get(TLS_KEYSTORE_PASSWORD, "");
        if (current.isBlank()) return "";
        return EncryptionUtil.encrypt(current);
    }

    private String loadTlsPassword() {
        String stored = settings.get(TLS_KEYSTORE_PASSWORD, "");
        if (stored.isBlank()) return "";
        if (!EncryptionUtil.isEncrypted(stored)) {
            throw new IllegalStateException("OPDS TLS keystore password is not protected");
        }
        return EncryptionUtil.decrypt(stored);
    }

    private void migrateLegacyTlsPasswordIfNeeded() {
        String current = settings.get(TLS_KEYSTORE_PASSWORD, "");
        if (current.isBlank() || EncryptionUtil.isCurrentEnvelope(current)) return;
        Map<String, String> replacement = new LinkedHashMap<>(settings.findByPrefix(P));
        replacement.put(TLS_KEYSTORE_PASSWORD, EncryptionUtil.encrypt(current));
        settings.replaceByPrefix(P, replacement);
    }

    /** Migrates the legacy plaintext key before it can be returned to an autostart/server caller. */
    private void migrateLegacyPasswordIfNeeded() {
        String current = settings.get(PASSWORD, "");
        if (current.isBlank() || OpdsPasswordHash.isHash(current)) return;
        Map<String, String> replacement = new LinkedHashMap<>(settings.findByPrefix(P));
        replacement.put(PASSWORD, OpdsPasswordHash.hash(current));
        settings.replaceByPrefix(P, replacement);
    }
}
