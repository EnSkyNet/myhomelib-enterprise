package com.myhomelibcorp.converter.toolchain;

public enum ConversionFailureKind {
    UNAVAILABLE,
    UNSUPPORTED,
    INVALID_INPUT,
    SECURITY_REJECTED,
    TIMEOUT,
    CANCELLED,
    ENGINE_CRASH,
    OUTPUT_INVALID,
    IO_FAILURE
}
