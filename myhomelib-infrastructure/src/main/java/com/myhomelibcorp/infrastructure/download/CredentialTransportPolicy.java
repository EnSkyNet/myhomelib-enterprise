package com.myhomelibcorp.infrastructure.download;

import java.net.URI;

/** Central invariant: reusable Basic Auth credentials are never sent over plaintext HTTP. */
final class CredentialTransportPolicy {
    private CredentialTransportPolicy() { }

    static void requireHttpsWhenCredentialsPresent(URI uri, String username) {
        if (username == null || username.isBlank()) return;
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SecurityException("Credentials online-колекції дозволено передавати лише через HTTPS");
        }
    }
}
