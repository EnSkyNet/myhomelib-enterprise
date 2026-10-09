package com.myhomelibcorp.application.kobo;

import java.time.Instant;

public record KoboDeviceStatus(
        String deviceId,
        boolean enabled,
        boolean secureStoreAvailable,
        String secureStoreBackend,
        Instant lastSyncAt,
        String lastError
) {
    public KoboDeviceStatus {
        deviceId = deviceId == null ? "" : deviceId;
        secureStoreBackend = secureStoreBackend == null ? "" : secureStoreBackend;
        lastError = lastError == null ? "" : lastError;
    }
}
