package com.myhomelibcorp.application.opds;

public record OpdsServerSettings(
        String bindAddress,
        int port,
        boolean basicAuthEnabled,
        String username,
        String password,
        boolean autostart,
        OpdsTlsSettings tls,
        OpdsSecurityLimits limits) {

    public OpdsServerSettings {
        bindAddress = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress.trim();
        port = Math.max(1, Math.min(65535, port));
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
        tls = tls == null ? OpdsTlsSettings.disabled() : tls;
        limits = limits == null ? OpdsSecurityLimits.defaults() : limits;
    }

    /** Backwards-compatible constructor used by existing callers and tests. */
    public OpdsServerSettings(
            String bindAddress,
            int port,
            boolean basicAuthEnabled,
            String username,
            String password,
            boolean autostart) {
        this(bindAddress, port, basicAuthEnabled, username, password, autostart,
                OpdsTlsSettings.disabled(), OpdsSecurityLimits.defaults());
    }

    public static OpdsServerSettings defaults() {
        return new OpdsServerSettings("127.0.0.1", 8088, false, "", "", false,
                OpdsTlsSettings.disabled(), OpdsSecurityLimits.defaults());
    }
}
