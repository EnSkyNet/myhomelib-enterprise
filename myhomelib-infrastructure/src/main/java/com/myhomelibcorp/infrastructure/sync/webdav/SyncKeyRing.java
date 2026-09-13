package com.myhomelibcorp.infrastructure.sync.webdav;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Active sync-encryption key plus bounded legacy keys retained only during rotation. */
public final class SyncKeyRing {
    public record Key(String id, byte[] bytes) {
        public Key {
            if (id == null || !id.matches("[a-f0-9]{16}")) {
                throw new IllegalArgumentException("sync key id must be 16 lowercase hex characters");
            }
            if (bytes == null || bytes.length != 32) {
                throw new IllegalArgumentException("AES-256 sync key must contain exactly 32 bytes");
            }
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override public byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }
    }

    private final Key active;
    private final Map<String, Key> byId;

    public SyncKeyRing(byte[] activeKey, byte[]... legacyKeys) {
        this.active = key(activeKey);
        Map<String, Key> keys = new LinkedHashMap<>();
        keys.put(active.id(), active);
        if (legacyKeys != null) {
            for (byte[] legacy : legacyKeys) {
                if (legacy == null || legacy.length == 0) continue;
                Key key = key(legacy);
                keys.putIfAbsent(key.id(), key);
            }
        }
        if (keys.size() > 4) throw new IllegalArgumentException("sync key ring supports at most 4 keys");
        this.byId = Map.copyOf(keys);
    }

    public Key active() { return active; }

    public Key require(String id) {
        Key key = byId.get(id);
        if (key == null) throw new SecurityException("Sync payload references an unavailable encryption key");
        return key;
    }

    public List<Key> candidates() { return List.copyOf(byId.values()); }

    public boolean isActive(String id) { return active.id().equals(id); }

    public static String keyId(byte[] bytes) {
        return key(bytes).id();
    }

    private static Key key(byte[] bytes) {
        if (bytes == null || bytes.length != 32) {
            throw new IllegalArgumentException("AES-256 sync key must contain exactly 32 bytes");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return new Key(java.util.HexFormat.of().formatHex(digest, 0, 8), bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
