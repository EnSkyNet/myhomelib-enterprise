package com.myhomelibcorp.application.conversion.diagnostics;

import java.util.List;

public interface ConverterDiagnosticsPort {
    List<ConverterToolStatus> snapshot();
    List<ConverterToolStatus> refresh();
    List<ConverterToolStatus> probe(ConverterDiagnosticsRequest request);
}
