package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.stereotype.Component;

import java.net.CookieHandler;
import java.net.http.HttpClient;
import java.util.Objects;

/**
 * Owns the process-wide stateless online {@link HttpClient}.
 *
 * <p>{@code java.net.http.HttpClient} is thread-safe and intentionally designed for reuse. Reusing
 * one client preserves the connection/TLS pools and, more importantly for the desktop runtime,
 * avoids one selector-manager thread per metadata/translation/download adapter. Stateful cookie
 * sessions remain isolated and are created explicitly through {@link #isolated(CookieHandler)}.</p>
 */
@Component
public final class OnlineHttpClientProvider {
    private final OnlineHttpPolicy policy;
    private volatile HttpClient shared;

    public OnlineHttpClientProvider(ApplicationSettingsPort settings) {
        this.policy = new OnlineHttpPolicy(Objects.requireNonNull(settings, "settings"));
    }

    /** Returns the shared no-cookie client, creating it only on first real consumer. */
    public HttpClient shared() {
        HttpClient current = shared;
        if (current != null) return current;
        synchronized (this) {
            current = shared;
            if (current == null) {
                current = policy.create(null);
                shared = current;
            }
            return current;
        }
    }

    /** Creates an isolated client when a workflow owns mutable cookie state. */
    public HttpClient isolated(CookieHandler cookies) {
        return policy.create(Objects.requireNonNull(cookies, "cookies"));
    }
}
