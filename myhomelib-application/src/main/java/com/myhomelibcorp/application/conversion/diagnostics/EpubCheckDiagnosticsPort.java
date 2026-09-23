package com.myhomelibcorp.application.conversion.diagnostics;

/** Diagnostics boundary for the optional EPUBCheck acceptance validator. */
public interface EpubCheckDiagnosticsPort {
    ConverterToolStatus snapshot();
    ConverterToolStatus probe(String executable);
}
