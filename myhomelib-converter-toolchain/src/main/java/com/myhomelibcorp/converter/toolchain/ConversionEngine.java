package com.myhomelibcorp.converter.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface ConversionEngine {
    ConversionEngineId id();
    boolean isAvailable();
    List<ConversionStep> capabilities();

    ConversionStepResult convert(ConversionStep step,
                                 Path source,
                                 Path target,
                                 BooleanSupplier cancelled,
                                 long maxOutputBytes) throws ConversionToolchainException;
}
