package com.myhomelibcorp.startup;

/** Explicit startup failure semantics. */
public enum StartupFailurePolicy {
    /** Application startup must stop because continuing would expose an unusable/unsafe state. */
    REQUIRED,
    /** Failure is recorded and logged, but the desktop may continue in a degraded mode. */
    BEST_EFFORT
}
