package com.myhomelibcorp.application.conversion.diagnostics;

import java.time.Instant;

public record ConverterToolStatus(String id, boolean available, String executablePath, String version,
                                  String diagnostic, Instant checkedAt) {
    public ConverterToolStatus { id=safe(id);executablePath=safe(executablePath);version=safe(version);diagnostic=safe(diagnostic);checkedAt=checkedAt==null?Instant.now():checkedAt; }
    private static String safe(String v){return v==null?"":v;}
}
