package com.myhomelibcorp.application.metadata;

/** Common failure vocabulary shared by all metadata providers. */
public enum MetadataProviderErrorKind {
    CANCELLED,
    TIMEOUT,
    RATE_LIMITED,
    UNAVAILABLE,
    AUTHENTICATION,
    INVALID_RESPONSE,
    FAILED
}
