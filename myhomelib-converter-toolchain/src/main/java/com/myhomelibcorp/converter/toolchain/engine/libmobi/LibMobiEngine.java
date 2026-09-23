package com.myhomelibcorp.converter.toolchain.engine.libmobi;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.converter.toolchain.*;
import com.myhomelibcorp.converter.toolchain.discovery.ConverterToolRegistry;
import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.converter.toolchain.discovery.ToolInstallation;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRequest;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolResult;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

@Component
public class LibMobiEngine implements ConversionEngine {
    private static final Logger log = LoggerFactory.getLogger(LibMobiEngine.class);
    private final ConverterToolRegistry tools;
    private final ExternalToolRunner runner;
    private final ApplicationSettingsPort settings;
    private final MobiToolCommandBuilder commands = new MobiToolCommandBuilder();

    public LibMobiEngine(ConverterToolRegistry tools, ExternalToolRunner runner, ApplicationSettingsPort settings) {
        this.tools = tools;
        this.runner = runner;
        this.settings = settings;
    }

    @Override public ConversionEngineId id() { return ConversionEngineId.LIBMOBI; }
    @Override public boolean isAvailable() { return tools.libmobi().available(); }
    @Override public List<ConversionStep> capabilities() { return LibMobiCapabilities.all(); }

    @Override
    public ConversionStepResult convert(ConversionStep step, Path source, Path target,
                                        BooleanSupplier cancelled, long maxOutputBytes)
            throws ConversionToolchainException {
        ToolInstallation installation = tools.libmobi();
        if (!installation.available()) throw new ConversionToolchainException(ConversionFailureKind.UNAVAILABLE, "libmobi mobitool is not available");
        try {
            source = ConversionPathSafety.requireSafeRegularFile(source, "libmobi input");
            target = ConversionPathSafety.prepareTarget(target, "libmobi output");
        } catch (Exception unsafe) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "Cannot prepare libmobi input/output path", unsafe);
        }
        Path work = target.getParent().resolve("libmobi-" + java.util.UUID.randomUUID());
        try {
            work = ConversionPathSafety.createDirectoriesNoLinks(work, "libmobi workspace");
            String ext = "." + step.sourceFormat().replaceAll("[^a-z0-9]", "");
            Path stagedSource = work.resolve("source" + ext);
            ConversionPathSafety.copyFreshFile(source, stagedSource, "libmobi staging copy");
            if (Files.mismatch(source, stagedSource) != -1L)
                throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "libmobi staging copy verification failed");
            Path generated = work.resolve("source.epub");
            int timeout = Math.max(10, Math.min(3600, settings.getInt("converter.toolchain.libmobi.timeoutSeconds", 300)));
            ExternalToolResult result = runner.run(new ExternalToolRequest(
                    commands.build(installation.executable(), stagedSource, work),
                    work, Duration.ofSeconds(timeout), 256 * 1024, cancelled, generated, maxOutputBytes));
            log.debug("libmobi conversion {} -> epub exited {}: {}", step.sourceFormat(), result.exitCode(), result.mergedOutput());
            if (result.exitCode() != 0) {
                throw new ConversionToolchainException(classify(result.mergedOutput()),
                        "libmobi conversion failed with exit code " + result.exitCode());
            }
            try {
                ConversionPathSafety.requireSafeRegularFile(generated, "libmobi generated EPUB");
            } catch (Exception missingOrUnsafe) {
                ConversionFailureKind missingOutputKind = classifyMissingOutput(result.mergedOutput());
                throw new ConversionToolchainException(missingOutputKind,
                        "libmobi did not create a safe EPUB output", missingOrUnsafe);
            }
            ConversionPathSafety.moveNoReplace(generated, target, "libmobi output publication");
            ConversionPathSafety.requireSafeRegularFile(target, "libmobi output");
            return new ConversionStepResult(step, target, result.durationMs(), Files.size(target));
        } catch (ConversionToolchainException e) {
            throw e;
        } catch (Exception e) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "libmobi conversion failed", e);
        } finally {
            com.myhomelibcorp.converter.toolchain.workspace.WorkspaceCleaner.deleteRecursively(work);
        }
    }

    private ConversionFailureKind classify(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        // MyHomeLib intentionally has no DRM/PID/serial workflow.  An encrypted
        // input is therefore a security-policy rejection, not a format fallback:
        // do not silently hand it to Calibre or another provider.
        if (lower.contains("document is encrypted")
                || lower.contains("drm key not found")
                || lower.contains("invalid drm pid")
                || lower.contains("drm support not included")
                || lower.contains("drm expired")) {
            return ConversionFailureKind.SECURITY_REJECTED;
        }
        if (lower.contains("can't create epub format from print replica")
                || lower.contains("unsupported format")
                || lower.contains("unsupported document format")) {
            return ConversionFailureKind.UNSUPPORTED;
        }
        if (lower.contains("invalid") || lower.contains("parsing") || lower.contains("failed to load")) {
            return ConversionFailureKind.INVALID_INPUT;
        }
        return ConversionFailureKind.ENGINE_CRASH;
    }

    private ConversionFailureKind classifyMissingOutput(String output) {
        ConversionFailureKind classified = classify(output);
        return classified == ConversionFailureKind.UNSUPPORTED
                ? ConversionFailureKind.UNSUPPORTED : ConversionFailureKind.OUTPUT_INVALID;
    }
}
