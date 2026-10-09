package com.myhomelibcorp.converter.toolchain.engine;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.converter.toolchain.process.BoundedOutputCapture;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRequest;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolResult;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Common mechanics for native conversion engines; format-specific policy stays in each engine. */
public final class ExternalConversionEngineSupport {
    private static final int MIN_TIMEOUT_SECONDS = 10;
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private ExternalConversionEngineSupport() { }

    public static int timeoutSeconds(ApplicationSettingsPort settings, String key, int defaultValue) {
        Objects.requireNonNull(settings, "settings");
        int configured = settings.getInt(key, defaultValue);
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, configured));
    }

    public static ExternalToolResult run(
            ExternalToolRunner runner,
            List<String> argv,
            Path workingDirectory,
            int timeoutSeconds,
            BooleanSupplier cancelled,
            Path monitoredOutput,
            long maxOutputBytes) throws ConversionToolchainException {
        return Objects.requireNonNull(runner, "runner").run(new ExternalToolRequest(
                argv,
                workingDirectory,
                Duration.ofSeconds(timeoutSeconds),
                BoundedOutputCapture.DEFAULT_MAX_BYTES,
                cancelled,
                monitoredOutput,
                maxOutputBytes));
    }

    public static void requireSuccessful(
            ExternalToolResult result,
            String engineName,
            Function<String, ConversionFailureKind> classifier) throws ConversionToolchainException {
        Objects.requireNonNull(result, "result");
        if (result.exitCode() == 0) return;
        ConversionFailureKind kind = Objects.requireNonNull(classifier, "classifier")
                .apply(result.mergedOutput());
        throw new ConversionToolchainException(kind,
                engineName + " conversion failed with exit code " + result.exitCode());
    }

    public static void requireSafeOutput(Path output, String label, String engineName)
            throws ConversionToolchainException {
        try {
            ConversionPathSafety.requireSafeRegularFile(output, label);
        } catch (Exception unsafe) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                    engineName + " produced an unsafe output", unsafe);
        }
    }

    public static long sizeOrUnknown(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return -1L;
        }
    }
}
