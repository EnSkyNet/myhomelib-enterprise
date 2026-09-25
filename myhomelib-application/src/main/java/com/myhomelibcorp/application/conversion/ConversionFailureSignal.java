package com.myhomelibcorp.application.conversion;

/**
 * Provider-neutral signal used by application orchestration to decide whether a failed
 * conversion may safely fall through to the next lower-priority provider.
 *
 * <p>Implementations may return {@code true} only when trying another independent provider is
 * safe and meaningful. Bad input, cancellation, security rejection and shared filesystem
 * corruption remain hard stops. Provider-local unavailability, unsupported routes, engine
 * crashes/timeouts and provider-specific invalid output may fall through to a lower-priority
 * provider so AUTO mode actually behaves as a resilient fallback chain.</p>
 */
public interface ConversionFailureSignal {
    boolean mayTryNextProvider();
}
