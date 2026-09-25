package com.myhomelibcorp.converter.toolchain;

import com.myhomelibcorp.application.conversion.ConversionFailureSignal;

public class ConversionToolchainException extends Exception implements ConversionFailureSignal {
    private final ConversionFailureKind kind;

    public ConversionToolchainException(ConversionFailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ConversionToolchainException(ConversionFailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public ConversionFailureKind kind() { return kind; }

    @Override
    public boolean mayTryNextProvider() {
        return switch (kind) {
            case UNAVAILABLE, UNSUPPORTED, ENGINE_CRASH, TIMEOUT, OUTPUT_INVALID -> true;
            case INVALID_INPUT, SECURITY_REJECTED, CANCELLED, IO_FAILURE -> false;
        };
    }
}

