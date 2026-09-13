package com.myhomelibcorp.infrastructure.sync.webdav;

import java.util.Base64;

/** Credentials and active/previous encryption keys for one WebDAV sync endpoint. */
public record WebDavSyncSecrets(String username, String password, String syncKeyBase64, String previousSyncKeyBase64) {
    public WebDavSyncSecrets(String username, String password, String syncKeyBase64) {
        this(username, password, syncKeyBase64, "");
    }

    public WebDavSyncSecrets {
        username = required(username, "username");
        password = required(password, "password");
        syncKeyBase64 = required(syncKeyBase64, "syncKeyBase64");
        validateKey(syncKeyBase64, "syncKeyBase64");
        previousSyncKeyBase64 = previousSyncKeyBase64 == null ? "" : previousSyncKeyBase64.trim();
        if (!previousSyncKeyBase64.isEmpty()) {
            validateKey(previousSyncKeyBase64, "previousSyncKeyBase64");
            if (previousSyncKeyBase64.equals(syncKeyBase64)) {
                throw new IllegalArgumentException("previous sync key must differ from active sync key");
            }
        }
    }

    public byte[] syncKeyBytes() { return decode(syncKeyBase64); }

    public SyncKeyRing syncKeyRing() {
        if (previousSyncKeyBase64.isEmpty()) return new SyncKeyRing(syncKeyBytes());
        return new SyncKeyRing(syncKeyBytes(), decode(previousSyncKeyBase64));
    }

    public boolean rotationInProgress() { return !previousSyncKeyBase64.isEmpty(); }

    private static void validateKey(String encoded, String field) {
        byte[] key;
        try {
            key = decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be Base64", e);
        }
        if (key.length != 32) throw new IllegalArgumentException(field + " must contain exactly 32 bytes");
    }

    private static byte[] decode(String encoded) { return Base64.getDecoder().decode(encoded); }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
