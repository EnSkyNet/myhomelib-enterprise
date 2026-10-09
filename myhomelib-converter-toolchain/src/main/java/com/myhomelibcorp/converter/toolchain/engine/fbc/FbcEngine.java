package com.myhomelibcorp.converter.toolchain.engine.fbc;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.converter.toolchain.*;
import com.myhomelibcorp.converter.toolchain.discovery.ConverterToolRegistry;
import com.myhomelibcorp.converter.toolchain.engine.ExternalConversionEngineSupport;
import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.converter.toolchain.discovery.ToolInstallation;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolResult;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
            int timeout = ExternalConversionEngineSupport.timeoutSeconds(
                    settings, "converter.toolchain.fbc.timeoutSeconds", 300);
            log.info("FBC_START executable='{}' source={} target={} timeoutSeconds={} inputBytes={}",
                    installation.executable(), step.sourceFormat(), step.targetFormat(), timeout,
                    ExternalConversionEngineSupport.sizeOrUnknown(actualSource));
            ExternalToolResult result = ExternalConversionEngineSupport.run(
                    runner,
                    commands.build(installation.executable(), actualSource, target, step.targetFormat()),
                    target.getParent(), timeout, cancelled, target, maxOutputBytes);
            log.info("FBC_DONE executable='{}' exitCode={} durationMs={} outputBytes={}",
                    installation.executable(), result.exitCode(), result.durationMs(),
                    ExternalConversionEngineSupport.sizeOrUnknown(target));
            log.debug("fb2cng conversion {} -> {} exited {}: {}", step.sourceFormat(), step.targetFormat(),
                    result.exitCode(), result.mergedOutput());
            ExternalConversionEngineSupport.requireSuccessful(result, "fb2cng", this::classify);
            ExternalConversionEngineSupport.requireSafeOutput(target, "fb2cng output", "fb2cng");
            return new ConversionStepResult(step, target, result.durationMs(),
                    ExternalConversionEngineSupport.sizeOrUnknown(target));
        } finally {
            if (stagedFb2 != null) {
                try { Files.deleteIfExists(stagedFb2); } catch (Exception cleanupFailure) {
                    log.debug("Cannot delete staged FB2.ZIP payload {}", stagedFb2, cleanupFailure);
                }
            }
        }
    }

    private ConversionFailureKind classify(String output) {
        String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (lower.contains("unsupported output format") || lower.contains("not supported")) {
            return ConversionFailureKind.UNSUPPORTED;
        }
        if (lower.contains("invalid") || lower.contains("malformed") || lower.contains("parse")) {
            return ConversionFailureKind.INVALID_INPUT;
        }
        return ConversionFailureKind.ENGINE_CRASH;
    }

}
