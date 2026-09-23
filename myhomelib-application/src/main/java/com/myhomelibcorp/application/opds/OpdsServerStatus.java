package com.myhomelibcorp.application.opds;

/**
 * Runtime OPDS status. User-facing text is represented by a stable localization key; raw server
 * or operating-system details are kept separately for logs/support diagnostics.
 */
public record OpdsServerStatus(
        boolean running,
        String bindAddress,
        int port,
        String baseUrl,
        boolean exposedBeyondLocalhost,
        String messageKey,
        String technicalDetail) {
    public OpdsServerStatus {
        messageKey = messageKey == null || messageKey.isBlank() ? "ui.opds.status.stopped" : messageKey;
        technicalDetail = technicalDetail == null ? "" : technicalDetail;
    }

    public static OpdsServerStatus stopped() {
        return new OpdsServerStatus(false, "", 0, "", false, "ui.opds.status.stopped", "");
    }

    public String healthUrl() {
        if (!running || bindAddress == null || bindAddress.isBlank() || port <= 0) return "";
        String scheme = baseUrl != null && baseUrl.regionMatches(true, 0, "https://", 0, 8) ? "https" : "http";
        String host = bindAddress.contains(":") && !bindAddress.startsWith("[") ? "[" + bindAddress + "]" : bindAddress;
        return scheme + "://" + host + ":" + port + "/health";
    }
}
