package com.myhomelibcorp.application.opds;

public record OpdsServerSettings(
        String bindAddress,
        int port,
        boolean basicAuthEnabled,
        String username,
        String password,
        boolean autostart) {
    public OpdsServerSettings {
        bindAddress = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress.trim();
        port = Math.max(1, Math.min(65535, port));
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
    }
    public static OpdsServerSettings defaults() {
        return new OpdsServerSettings("127.0.0.1", 8088, false, "", "", false);
    }
}
