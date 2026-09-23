package com.myhomelibcorp.application.conversion;

/**
 * Provider-neutral signal used by application orchestration to decide whether a failed
 * conversion may safely fall through to the next lower-priority provider.
 *
 * <p>Implementations must return {@code true} only for failures that do not indicate
 * bad input, cancellation, security rejection, timeout, engine crash, invalid output,
 * or I/O corruption. In v1 this is intended for dynamic UNAVAILABLE/UNSUPPORTED cases.</p>
 */
public interface ConversionFailureSignal {
    boolean mayTryNextProvider();
}
