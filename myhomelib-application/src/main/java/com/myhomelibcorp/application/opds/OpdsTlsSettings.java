package com.myhomelibcorp.application.opds;

/**
 * TLS transport settings for the embedded OPDS server.
 *
 * <p>The keystore password is deliberately treated as an in-memory/runtime secret. The
 * settings service persists only non-secret TLS metadata; the OPDS adapter can additionally
 * resolve the password from a system property/environment variable at startup.</p>
 */
public record OpdsTlsSettings(
        boolean enabled,
        String keyStorePath,
        String keyStoreType,
        String keyStorePassword) {

    public OpdsTlsSettings {
        keyStorePath = keyStorePath == null ? "" : keyStorePath.trim();
        keyStoreType = keyStoreType == null || keyStoreType.isBlank() ? "PKCS12" : keyStoreType.trim();
        keyStorePassword = keyStorePassword == null ? "" : keyStorePassword;
    }

    public static OpdsTlsSettings disabled() {
        return new OpdsTlsSettings(false, "", "PKCS12", "");
    }

    public boolean hasKeyStorePath() {
        return !keyStorePath.isBlank();
    }
}
