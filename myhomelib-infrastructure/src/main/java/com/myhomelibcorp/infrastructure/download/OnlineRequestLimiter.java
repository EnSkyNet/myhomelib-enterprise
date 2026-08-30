package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared HTTP concurrency guard for UI and headless download paths.
 *
 * <p>Acquisition is cancellation-aware and deliberately bounded to short waits, so
 * cancelling a queued download does not have to wait for another large transfer to finish.
 * Host gates are reference-counted and removed after the last active/waiting request, so
 * arbitrary historical host names cannot accumulate for the lifetime of the process.</p>
 */
@Component
public final class OnlineRequestLimiter {
    private static final long ACQUIRE_POLL_MILLIS = 100L;

    private final Semaphore global;
    private final int perHostLimit;
    private final ConcurrentHashMap<String, HostGate> byHost = new ConcurrentHashMap<>();

    public OnlineRequestLimiter(ApplicationSettingsPort settings) {
        int globalLimit = clamp(settings.getInt("online.maxParallelDownloads", 2), 1, 32);
        this.perHostLimit = clamp(settings.getInt("online.maxParallelDownloadsPerHost", Math.min(2, globalLimit)), 1, globalLimit);
        this.global = new Semaphore(globalLimit, true);
    }

    public Permit acquire(URI uri, AtomicBoolean cancelFlag) throws IOException {
        AtomicBoolean cancel = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        String key = hostKey(uri);
        HostGate host = retainHost(key);
        boolean globalAcquired = false;
        boolean hostAcquired = false;
        try {
            acquireOne(global, cancel);
            globalAcquired = true;
            acquireOne(host.semaphore, cancel);
            hostAcquired = true;
            return new Permit(this, key, host);
        } catch (IOException e) {
            if (hostAcquired) host.semaphore.release();
            if (globalAcquired) global.release();
            releaseHostReference(key, host);
            throw e;
        }
    }

    private HostGate retainHost(String key) {
        return byHost.compute(key, (ignored, existing) -> {
            HostGate gate = existing == null ? new HostGate(perHostLimit) : existing;
            gate.references.incrementAndGet();
            return gate;
        });
    }

    private void releaseHostReference(String key, HostGate expected) {
        byHost.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) return current;
            int remaining = current.references.decrementAndGet();
            if (remaining < 0) throw new IllegalStateException("Online host-gate reference underflow");
            return remaining == 0 ? null : current;
        });
    }

    private static void acquireOne(Semaphore semaphore, AtomicBoolean cancel) throws IOException {
        try {
            while (true) {
                if (cancel.get() || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Завантаження скасовано під час очікування мережевого слота");
                }
                if (semaphore.tryAcquire(ACQUIRE_POLL_MILLIS, TimeUnit.MILLISECONDS)) return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Завантаження скасовано під час очікування мережевого слота", e);
        }
    }

    private static String hostKey(URI uri) {
        if (uri == null) return "<unknown>";
        String host = uri.getHost();
        if (host == null || host.isBlank()) return uri.getAuthority() == null ? "<unknown>" : uri.getAuthority().toLowerCase(Locale.ROOT);
        return host.toLowerCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class HostGate {
        private final Semaphore semaphore;
        private final AtomicInteger references = new AtomicInteger();

        private HostGate(int permits) {
            this.semaphore = new Semaphore(permits, true);
        }
    }

    public static final class Permit implements AutoCloseable {
        private final OnlineRequestLimiter owner;
        private final String hostKey;
        private final HostGate host;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(OnlineRequestLimiter owner, String hostKey, HostGate host) {
            this.owner = owner;
            this.hostKey = hostKey;
            this.host = host;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            host.semaphore.release();
            owner.global.release();
            owner.releaseHostReference(hostKey, host);
        }
    }
}
