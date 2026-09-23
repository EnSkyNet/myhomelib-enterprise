package com.myhomelibcorp.application.conversion.diagnostics;

public record ConverterDiagnosticsRequest(String fbcExecutable, String pandocExecutable, String libmobiExecutable) {
    public ConverterDiagnosticsRequest {
        fbcExecutable = clean(fbcExecutable);
        pandocExecutable = clean(pandocExecutable);
        libmobiExecutable = clean(libmobiExecutable);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
