package com.myhomelibcorp.domain.model.sync;

/** Version contract for portable user-data change bundles. */
public final class SyncSchema {
    public static final int CURRENT_VERSION = 1;
    public static final int MIN_SUPPORTED_VERSION = 1;

    private SyncSchema() {
    }

    public static boolean isSupported(int version) {
        return version >= MIN_SUPPORTED_VERSION && version <= CURRENT_VERSION;
    }

    public static void requireSupported(int version) {
        if (!isSupported(version)) {
            throw new IllegalArgumentException("Unsupported sync schema version: " + version);
        }
    }
}
