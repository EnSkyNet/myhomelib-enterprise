package com.myhomelibcorp.application.kobo;

/** Newly issued one-time-visible Kobo credential. toString deliberately redacts the secret. */
public final class KoboCredentialIssue {
    private final String deviceId;
    private final String token;

    public KoboCredentialIssue(String deviceId, String token) {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId is required");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is required");
        this.deviceId = deviceId;
        this.token = token;
    }
    public String deviceId() { return deviceId; }
    public String token() { return token; }
    @Override public String toString() { return "KoboCredentialIssue[deviceId=" + deviceId + ", token=<redacted>]"; }
}
