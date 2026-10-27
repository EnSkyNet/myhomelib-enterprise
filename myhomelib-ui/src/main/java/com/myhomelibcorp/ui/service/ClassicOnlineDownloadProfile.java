package com.myhomelibcorp.ui.service;

import java.util.Map;

/**
 * Optional A/B diagnostic preset derived from the tagged MyHomeLib 2.5.0-era
 * downloader (Indy HTTP). It changes only user-visible settings fields; callers
 * must explicitly save and restart so concurrency permits are reconstructed.
 *
 * <p>Deliberately does not downgrade TLS, disable certificate validation,
 * or override the user's selected proxy mode.</p>
 */
public final class ClassicOnlineDownloadProfile {
    private ClassicOnlineDownloadProfile() { }

    public static Map<String, String> values() {
        return Map.of(
                "online.connectTimeoutSeconds", "9",
                "online.readTimeoutSeconds", "90",
                "online.maxParallelDownloads", "1",
                "online.maxParallelDownloadsPerHost", "1",
                "online.userAgent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0; MAAU)");
    }
}
