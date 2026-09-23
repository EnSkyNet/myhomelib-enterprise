package com.myhomelibcorp.converter.toolchain.process;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@Component
public class ExternalToolRunner {

    public ExternalToolResult run(ExternalToolRequest request) throws ConversionToolchainException {
        long started = System.nanoTime();
        AtomicBoolean outputTooLarge = new AtomicBoolean(false);
        AtomicBoolean outputUnsafe = new AtomicBoolean(false);
        BooleanSupplier guardedCancel = () -> {
            if (request.cancelled().getAsBoolean()) return true;
            Path monitoredOutput = request.monitoredOutput();
            if (monitoredOutput != null) {
                try {
                    Path parent = monitoredOutput.toAbsolutePath().normalize().getParent();
                    if (parent == null) {
                        outputUnsafe.set(true);
                        return true;
                    }
                    ConversionPathSafety.requireSafeDirectoryPath(parent, "External converter output parent");
                    if (Files.exists(monitoredOutput, LinkOption.NOFOLLOW_LINKS)) {
                        BasicFileAttributes attrs = Files.readAttributes(
                                monitoredOutput, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (Files.isSymbolicLink(monitoredOutput) || attrs.isOther() || !attrs.isRegularFile()) {
                            outputUnsafe.set(true);
                            return true;
                        }
                        if (request.maxOutputBytes() < Long.MAX_VALUE && attrs.size() > request.maxOutputBytes()) {
                            outputTooLarge.set(true);
                            return true;
                        }
                    }
                } catch (IOException | RuntimeException unsafePath) {
                    outputUnsafe.set(true);
                    return true;
                }
            }
            return false;
        };

        final Path safeWorkingDirectory;
        try {
            safeWorkingDirectory = ConversionPathSafety.requireSafeDirectoryPath(
                    request.workingDirectory(), "External converter working directory");
        } catch (IOException | RuntimeException unsafeDirectory) {
            throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED,
                    "External converter working directory is unsafe", unsafeDirectory);
        }
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(request.argv()))
                .directory(safeWorkingDirectory.toFile());
        try {
            ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(
                    builder, null, request.timeout(), request.maxCaptureBytes(), guardedCancel);
            long durationMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            return new ExternalToolResult(result.exitCode(), result.stdoutText(), result.stderrText(),
                    result.stdoutTruncated(), result.stderrTruncated(), durationMs);
        } catch (ProcessExecutionSupport.ProcessTimeoutException e) {
            throw new ConversionToolchainException(ConversionFailureKind.TIMEOUT,
                    "External converter timed out after " + request.timeout().toSeconds() + " s", e);
        } catch (CancellationException e) {
            if (outputUnsafe.get()) {
                throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED,
                        "External converter created an unsafe output filesystem entry", e);
            }
            if (outputTooLarge.get()) {
                throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                        "External converter output exceeded the configured size limit", e);
            }
            throw new ConversionToolchainException(ConversionFailureKind.CANCELLED, "Conversion cancelled", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionToolchainException(ConversionFailureKind.CANCELLED, "Conversion interrupted", e);
        } catch (IOException e) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                    "Cannot start or communicate with external converter", e);
        } catch (RuntimeException e) {
            throw new ConversionToolchainException(ConversionFailureKind.ENGINE_CRASH,
                    "External converter execution failed", e);
        }
    }

    public ExternalToolResult probe(java.util.List<String> argv, java.nio.file.Path workingDirectory, Duration timeout)
            throws ConversionToolchainException {
        return run(new ExternalToolRequest(argv, workingDirectory, timeout, 32 * 1024,
                () -> false, null, Long.MAX_VALUE));
    }
}
