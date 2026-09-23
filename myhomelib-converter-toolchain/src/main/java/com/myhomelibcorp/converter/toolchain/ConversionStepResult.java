package com.myhomelibcorp.converter.toolchain;

import java.nio.file.Path;

public record ConversionStepResult(ConversionStep step, Path output, long durationMs, long outputBytes) { }
