package com.myhomelibcorp.converter.toolchain.engine.fbc;

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
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

@Component
public class FbcEngine implements ConversionEngine {
    private static final Logger log = LoggerFactory.getLogger(FbcEngine.class);
    private final ConverterToolRegistry tools;
    private final ExternalToolRunner runner;
    private final ApplicationSettingsPort settings;
    private final FbcCommandBuilder commands = new FbcCommandBuilder();
    private final SingleFb2ZipExtractor fb2ZipExtractor = new SingleFb2ZipExtractor();

    public FbcEngine(ConverterToolRegistry tools, ExternalToolRunner runner, ApplicationSettingsPort settings) {
        this.tools = tools;
        this.runner = runner;
        this.settings = settings;
    }

    @Override public ConversionEngineId id() { return ConversionEngineId.FBC; }
    @Override public boolean isAvailable() { return tools.fbc().available(); }
    @Override public List<ConversionStep> capabilities() { return FbcCapabilities.all(); }

    @Override
    public ConversionStepResult convert(ConversionStep step, Path source, Path target,
                                        BooleanSupplier cancelled, long maxOutputBytes)
            throws ConversionToolchainException {
        if (step.engineId() != id()) throw new ConversionToolchainException(ConversionFailureKind.UNSUPPORTED, "Wrong engine for step");
        ToolInstallation installation = tools.fbc();
        if (!installation.available()) throw new ConversionToolchainException(ConversionFailureKind.UNAVAILABLE, "fb2cng is not available");
        try {
            source = ConversionPathSafety.requireSafeRegularFile(source, "fb2cng input");
            target = ConversionPathSafety.prepareTarget(target, "fb2cng output");
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new java.nio.file.FileAlreadyExistsException(target.toString());
        } catch (Exception e) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "Cannot prepare fb2cng output", e);
        }
        Path stagedFb2 = null;
        Path actualSource = source;
        try {
            if ("fb2_zip".equals(step.sourceFormat())) {
                stagedFb2 = fb2ZipExtractor.extract(source, target.getParent(), cancelled);
                actualSource = stagedFb2;
            }
            int timeout = boundedTimeout(settings.getInt("converter.toolchain.fbc.timeoutSeconds", 300));
            ExternalToolResult result = runner.run(new ExternalToolRequest(
                    commands.build(installation.executable(), actualSource, target, step.targetFormat()),
                    target.getParent(), Duration.ofSeconds(timeout), 256 * 1024, cancelled, target, maxOutputBytes));
            log.debug("fb2cng conversion {} -> {} exited {}: {}", step.sourceFormat(), step.targetFormat(),
                    result.exitCode(), result.mergedOutput());
            if (result.exitCode() != 0) {
                throw new ConversionToolchainException(classify(result.mergedOutput()),
                        "fb2cng conversion failed with exit code " + result.exitCode());
            }
            try { ConversionPathSafety.requireSafeRegularFile(target, "fb2cng output"); }
            catch (Exception unsafe) { throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, "fb2cng produced an unsafe output", unsafe); }
            return new ConversionStepResult(step, target, result.durationMs(), size(target));
        } finally {
            if (stagedFb2 != null) {
                try { Files.deleteIfExists(stagedFb2); } catch (Exception cleanupFailure) {
                    log.debug("Cannot delete staged FB2.ZIP payload {}", stagedFb2, cleanupFailure);
                }
            }
        }
    }

    private ConversionFailureKind classify(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        if (lower.contains("unsupported output format") || lower.contains("not supported")) {
            return ConversionFailureKind.UNSUPPORTED;
        }
        if (lower.contains("invalid") || lower.contains("malformed") || lower.contains("parse")) {
            return ConversionFailureKind.INVALID_INPUT;
        }
        return ConversionFailureKind.ENGINE_CRASH;
    }

    private int boundedTimeout(int value) { return Math.max(10, Math.min(3600, value)); }
    private long size(Path path) { try { return Files.size(path); } catch (Exception ignored) { return -1; } }
}
