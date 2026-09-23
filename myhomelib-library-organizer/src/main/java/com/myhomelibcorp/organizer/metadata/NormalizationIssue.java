package com.myhomelibcorp.organizer.metadata;

public record NormalizationIssue(String code, String message) {
    public NormalizationIssue { code = code == null ? "" : code; message = message == null ? "" : message; }
}
