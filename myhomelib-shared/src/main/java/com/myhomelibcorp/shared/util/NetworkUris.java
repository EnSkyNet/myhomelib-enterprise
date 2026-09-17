package com.myhomelibcorp.shared.util;

import java.net.URI;

/** Stable URI predicates shared by HTTP-backed infrastructure adapters. */
public final class NetworkUris {
    private NetworkUris() { }

    public static boolean isLoopbackHttp(URI uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
