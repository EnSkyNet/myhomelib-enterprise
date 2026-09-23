package com.myhomelibcorp.organizer.fingerprint;

public record BookFingerprint(String fileSha256, String semanticSha256, long size, long modifiedMillis) {
    public BookFingerprint { fileSha256 = safe(fileSha256); semanticSha256 = safe(semanticSha256); }
    private static String safe(String value) { return value == null ? "" : value; }
}
