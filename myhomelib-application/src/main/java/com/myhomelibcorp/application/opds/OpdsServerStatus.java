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
}
