package com.myhomelibcorp.application.opds;

public record OpdsServerStatus(
        boolean running,
        String bindAddress,
        int port,
        String baseUrl,
        boolean exposedBeyondLocalhost,
        String message) {
    public static OpdsServerStatus stopped() {
        return new OpdsServerStatus(false, "", 0, "", false, "OPDS зупинено");
    }

    public String healthUrl() {
        if (!running || bindAddress == null || bindAddress.isBlank() || port <= 0) return "";
        String scheme = baseUrl != null && baseUrl.regionMatches(true, 0, "https://", 0, 8) ? "https" : "http";
        String host = bindAddress.contains(":") && !bindAddress.startsWith("[") ? "[" + bindAddress + "]" : bindAddress;
        return scheme + "://" + host + ":" + port + "/health";
    }
}
