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
        return kind == ConversionFailureKind.UNAVAILABLE || kind == ConversionFailureKind.UNSUPPORTED;
    }
}

