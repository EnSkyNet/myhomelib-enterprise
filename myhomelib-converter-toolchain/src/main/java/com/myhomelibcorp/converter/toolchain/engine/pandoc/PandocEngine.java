package com.myhomelibcorp.converter.toolchain.engine.pandoc;

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

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

@Component
public class PandocEngine implements ConversionEngine {
    private static final Logger log = LoggerFactory.getLogger(PandocEngine.class);
    private final ConverterToolRegistry tools;
    private final ExternalToolRunner runner;
    private final ApplicationSettingsPort settings;
    private final PandocCommandBuilder commands = new PandocCommandBuilder();

    public PandocEngine(ConverterToolRegistry tools, ExternalToolRunner runner, ApplicationSettingsPort settings) {
        this.tools = tools;
        this.runner = runner;
        this.settings = settings;
    }

    @Override public ConversionEngineId id() { return ConversionEngineId.PANDOC; }
    @Override public boolean isAvailable() { return tools.pandoc().available(); }
    @Override public List<ConversionStep> capabilities() { return PandocCapabilities.all(); }

    @Override
    public ConversionStepResult convert(ConversionStep step, Path source, Path target,
                                        BooleanSupplier cancelled, long maxOutputBytes)
            throws ConversionToolchainException {
        ToolInstallation installation = tools.pandoc();
        if (!installation.available()) throw new ConversionToolchainException(ConversionFailureKind.UNAVAILABLE, "Pandoc is not available");
        try {
            source = ConversionPathSafety.requireSafeRegularFile(source, "Pandoc input");
            target = ConversionPathSafety.prepareTarget(target, "Pandoc output");
        } catch (Exception unsafe) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "Cannot prepare Pandoc input/output path", unsafe);
        }
        if ("txt".equals(step.sourceFormat()) && !isStrictUtf8(source)) {
            // Pandoc consumes textual input as Unicode. Do not guess a legacy code page inside the
            // primary toolchain; a compatibility provider such as Calibre may have explicit legacy
            // encoding heuristics/settings and can be tried through the provider fallback contract.
            throw new ConversionToolchainException(ConversionFailureKind.UNSUPPORTED,
                    "Pandoc TXT route requires strict UTF-8 input");
        }
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                    "Pandoc output path already exists: " + target);
        }
        int timeout = Math.max(10, Math.min(3600, settings.getInt("converter.toolchain.pandoc.timeoutSeconds", 300)));
        ExternalToolResult result = runner.run(new ExternalToolRequest(
                commands.build(installation.executable(), source, target, step.sourceFormat(), step.targetFormat()),
                target.getParent(), Duration.ofSeconds(timeout), 256 * 1024, cancelled, target, maxOutputBytes));
        log.debug("Pandoc conversion {} -> {} exited {}: {}", step.sourceFormat(), step.targetFormat(), result.exitCode(), result.mergedOutput());
        if (result.exitCode() != 0) {
            throw new ConversionToolchainException(classify(result.mergedOutput()),
                    "Pandoc conversion failed with exit code " + result.exitCode());
        }
        try { ConversionPathSafety.requireSafeRegularFile(target, "Pandoc output"); }
        catch (Exception unsafe) { throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, "Pandoc produced an unsafe output", unsafe); }
        return new ConversionStepResult(step, target, result.durationMs(), size(target));
    }

    private boolean isStrictUtf8(Path source) throws ConversionToolchainException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        char[] buffer = new char[16 * 1024];
        try (Reader reader = new InputStreamReader(Files.newInputStream(source, java.nio.file.LinkOption.NOFOLLOW_LINKS), decoder)) {
            while (reader.read(buffer) >= 0) { /* streaming validation only */ }
            return true;
        } catch (MalformedInputException | UnmappableCharacterException malformed) {
            return false;
        } catch (Exception io) {
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                    "Cannot validate TXT encoding before Pandoc conversion", io);
        }
    }

    private ConversionFailureKind classify(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        if (lower.contains("could not find data file")
                || lower.contains("data file") && lower.contains("not found")) {
            // A sandboxed Pandoc binary without embedded data files cannot service formats
            // such as DOCX reliably; another provider may still be usable.
            return ConversionFailureKind.UNAVAILABLE;
        }
        if (lower.contains("unknown reader") || lower.contains("unknown writer")
                || lower.contains("unsupported input format") || lower.contains("unsupported output format")) {
            return ConversionFailureKind.UNSUPPORTED;
        }
        if (lower.contains("could not parse") || lower.contains("invalid") || lower.contains("error parsing")) {
            return ConversionFailureKind.INVALID_INPUT;
        }
        return ConversionFailureKind.ENGINE_CRASH;
    }

    private long size(Path path) { try { return Files.size(path); } catch (Exception ignored) { return -1; } }
}
