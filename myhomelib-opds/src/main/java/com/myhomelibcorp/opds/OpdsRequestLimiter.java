package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.OpdsSecurityLimits;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Small in-process guard for the JDK OPDS sidecar. It bounds concurrently executing requests and
 * keeps per-client authentication failure windows without retaining unbounded history.
 */
final class OpdsRequestLimiter {
    private static final int MAX_TRACKED_CLIENTS = 4096;

    private final Semaphore requestPermits;
    private final int failureLimit;
    private final long authWindowMillis;
    private final long authBlockMillis;
    private final ConcurrentHashMap<String, AuthState> authStates = new ConcurrentHashMap<>();

    OpdsRequestLimiter(OpdsSecurityLimits limits) {
        this.requestPermits = new Semaphore(limits.maxConcurrentRequests(), true);
        this.failureLimit = limits.authFailuresPerWindow();
        this.authWindowMillis = Duration.ofSeconds(limits.authWindowSeconds()).toMillis();
        this.authBlockMillis = Duration.ofSeconds(limits.authBlockSeconds()).toMillis();
    }

    RequestPermit tryAcquireRequest() {
        return requestPermits.tryAcquire() ? new RequestPermit(requestPermits) : null;
    }

    AuthThrottle beforeAuthentication(String clientKey) {
        long now = System.currentTimeMillis();
        AuthState state = authStates.get(clientKey);
        if (state == null) return AuthThrottle.allowed();
        AuthThrottle result = state.before(now, authWindowMillis);
        if (!result.blocked() && state.isIdle(now, authWindowMillis)) {
            authStates.remove(clientKey, state);
        }
        return result;
    }

    AuthThrottle authenticationFailed(String clientKey) {
        long now = System.currentTimeMillis();
        pruneIfNeeded(now);
        AuthState state = authStates.computeIfAbsent(clientKey, ignored -> new AuthState());
        return state.fail(now, failureLimit, authWindowMillis, authBlockMillis);
    }

    void authenticationSucceeded(String clientKey) {
        authStates.remove(clientKey);
    }

    private void pruneIfNeeded(long now) {
        if (authStates.size() < MAX_TRACKED_CLIENTS) return;
        Iterator<Map.Entry<String, AuthState>> it = authStates.entrySet().iterator();
        while (it.hasNext() && authStates.size() >= MAX_TRACKED_CLIENTS) {
            Map.Entry<String, AuthState> entry = it.next();
            if (entry.getValue().isIdle(now, Math.max(authWindowMillis, authBlockMillis))) {
                authStates.remove(entry.getKey(), entry.getValue());
            }
        }
        if (authStates.size() >= MAX_TRACKED_CLIENTS) {
            // Bound memory even under an IP-spoof/proxy churn scenario. Removing one old state is safe:
            // it can only make that one client retry authentication from a clean window.
            authStates.keySet().stream().findAny().ifPresent(authStates::remove);
        }
    }

    record AuthThrottle(boolean blocked, long retryAfterSeconds) {
        static AuthThrottle allowed() { return new AuthThrottle(false, 0); }
        static AuthThrottle blockedUntil(long now, long blockedUntil) {
            long millis = Math.max(1, blockedUntil - now);
            return new AuthThrottle(true, Math.max(1, (millis + 999) / 1000));
        }
    }

    static final class RequestPermit implements AutoCloseable {
        private final Semaphore permits;
        private boolean closed;

        private RequestPermit(Semaphore permits) { this.permits = permits; }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                permits.release();
            }
        }
    }

    private static final class AuthState {
        private long windowStartedAt;
        private int failures;
        private long blockedUntil;

        synchronized AuthThrottle before(long now, long windowMillis) {
            if (blockedUntil > now) return AuthThrottle.blockedUntil(now, blockedUntil);
            if (blockedUntil != 0 && blockedUntil <= now) reset(now);
            if (windowStartedAt != 0 && now - windowStartedAt >= windowMillis) reset(now);
            return AuthThrottle.allowed();
        }

        synchronized AuthThrottle fail(long now, int failureLimit, long windowMillis, long blockMillis) {
            if (blockedUntil > now) return AuthThrottle.blockedUntil(now, blockedUntil);
            if (windowStartedAt == 0 || now - windowStartedAt >= windowMillis) {
                windowStartedAt = now;
                failures = 0;
                blockedUntil = 0;
            }
            failures++;
            if (failures >= failureLimit) {
                blockedUntil = now + blockMillis;
                return AuthThrottle.blockedUntil(now, blockedUntil);
            }
            return AuthThrottle.allowed();
        }

        synchronized boolean isIdle(long now, long ttlMillis) {
            long last = Math.max(windowStartedAt, blockedUntil);
            return last == 0 || now - last > ttlMillis;
        }

        private void reset(long now) {
            windowStartedAt = now;
            failures = 0;
            blockedUntil = 0;
        }
    }
}
