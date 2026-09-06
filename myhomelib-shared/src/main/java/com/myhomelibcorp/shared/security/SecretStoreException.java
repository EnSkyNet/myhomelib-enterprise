package com.myhomelibcorp.shared.security;

public final class SecretStoreException extends RuntimeException {
    public SecretStoreException(String message) { super(message); }
    public SecretStoreException(String message, Throwable cause) { super(message, cause); }
}
