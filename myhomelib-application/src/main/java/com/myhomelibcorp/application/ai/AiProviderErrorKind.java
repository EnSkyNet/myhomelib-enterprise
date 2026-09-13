package com.myhomelibcorp.application.ai;

public enum AiProviderErrorKind {
    CANCELLED,
    TIMEOUT,
    CONSENT_REQUIRED,
    AUTHENTICATION,
    RATE_LIMITED,
    UNAVAILABLE,
    INVALID_RESPONSE,
    FAILED
}
