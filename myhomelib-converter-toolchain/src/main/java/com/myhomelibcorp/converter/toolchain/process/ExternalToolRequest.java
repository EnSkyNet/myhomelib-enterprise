package com.myhomelibcorp.converter.toolchain.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public record ExternalToolRequest(
        List<String> argv,
        Path workingDirectory,
        Duration timeout,
        int maxCaptureBytes,
        BooleanSupplier cancelled,
        Path monitoredOutput,
        long maxOutputBytes) {

    public ExternalToolRequest {
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (argv.isEmpty()) throw new IllegalArgumentException("argv cannot be empty");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        maxCaptureBytes = maxCaptureBytes <= 0 ? 256 * 1024 : maxCaptureBytes;
        cancelled = cancelled == null ? () -> false : cancelled;
        maxOutputBytes = maxOutputBytes <= 0 ? Long.MAX_VALUE : maxOutputBytes;
    }
}
