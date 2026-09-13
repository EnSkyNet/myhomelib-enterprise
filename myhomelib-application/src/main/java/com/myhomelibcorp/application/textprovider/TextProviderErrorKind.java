package com.myhomelibcorp.application.textprovider;

/** Common failure vocabulary for dictionary/translation provider SPIs. */
public enum TextProviderErrorKind {
    CANCELLED,
    TIMEOUT,
    RATE_LIMITED,
    UNAVAILABLE,
    AUTHENTICATION,
    NOT_FOUND,
    INVALID_RESPONSE,
    FAILED
}
