package com.myhomelibcorp.application.opds;

import java.time.Instant;
import java.util.Set;

/** Public token metadata. Raw token material and stored hashes are intentionally not exposed. */
public record OpdsAccessTokenInfo(
        String id,
        String deviceName,
        Set<OpdsTokenScope> scopes,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt) {

    public OpdsAccessTokenInfo {
        id = id == null ? "" : id;
        deviceName = deviceName == null ? "" : deviceName;
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public boolean revoked() { return revokedAt != null; }
}
