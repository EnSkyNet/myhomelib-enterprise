package com.myhomelibcorp.application.ai;

/** Per-invocation consent. Persistent per-book opt-in is checked independently by the host service. */
public record AiConsent(boolean allowNetwork, boolean allowBookContent) {
    public static AiConsent none() {
        return new AiConsent(false, false);
    }
}
