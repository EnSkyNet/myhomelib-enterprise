package com.myhomelibcorp.application.opds;

/** Security/back-pressure limits for the embedded OPDS sidecar. */
public record OpdsSecurityLimits(
        int maxConcurrentRequests,
        int listenBacklog,
        int authFailuresPerWindow,
        int authWindowSeconds,
        int authBlockSeconds,
        boolean healthRequiresAuthWhenExposed) {

    public OpdsSecurityLimits {
        maxConcurrentRequests = clamp(maxConcurrentRequests, 1, 1024);
        listenBacklog = clamp(listenBacklog, 1, 1024);
        authFailuresPerWindow = clamp(authFailuresPerWindow, 1, 1000);
        authWindowSeconds = clamp(authWindowSeconds, 1, 3600);
        authBlockSeconds = clamp(authBlockSeconds, 1, 86400);
    }

    public static OpdsSecurityLimits defaults() {
        return new OpdsSecurityLimits(64, 64, 8, 60, 120, true);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
