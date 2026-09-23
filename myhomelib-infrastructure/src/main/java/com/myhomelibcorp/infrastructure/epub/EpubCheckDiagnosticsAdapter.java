package com.myhomelibcorp.infrastructure.epub;

import com.myhomelibcorp.application.conversion.diagnostics.ConverterToolStatus;
import com.myhomelibcorp.application.conversion.diagnostics.EpubCheckDiagnosticsPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public final class EpubCheckDiagnosticsAdapter implements EpubCheckDiagnosticsPort {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private final ApplicationSettingsPort settings;
    private final EpubCheckToolCommandFactory commands;

    public EpubCheckDiagnosticsAdapter(ApplicationSettingsPort settings, EpubCheckToolCommandFactory commands) {
        this.settings = settings;
        this.commands = commands;
    }

    @Override
    public ConverterToolStatus snapshot() {
        return probe(settings == null ? "" : settings.get("converter.epubcheck.executable", ""));
    }

    @Override
    public ConverterToolStatus probe(String executable) {
        Instant checkedAt = Instant.now();
        EpubCheckToolCommandFactory.ResolvedTool resolved = commands.resolve(executable);
        if (!resolved.available()) {
            return new ConverterToolStatus("epubcheck", false, resolved.displayPath(), "", resolved.source(), checkedAt);
        }
        List<String> command = commands.versionCommand(executable);
        try {
            ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(command, null, TIMEOUT, 128 * 1024);
            String output = diagnostic(result);
            boolean available = result.exitCode() == 0 && looksLikeEpubCheck(output);
            String version = available ? firstLine(output) : "";
            String diagnostic = available
                    ? (resolved.source().isBlank() ? "Available" : "Available via " + resolved.source())
                    : (result.exitCode() == 0
                        ? "Unexpected EPUBCheck version output"
                        : (output.isBlank() ? "EPUBCheck exit code " + result.exitCode() : output));
            return new ConverterToolStatus("epubcheck", available, resolved.displayPath(), version, diagnostic, checkedAt);
        } catch (IOException failure) {
            return new ConverterToolStatus("epubcheck", false, resolved.displayPath(), "", safe(failure.getMessage()), checkedAt);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new ConverterToolStatus("epubcheck", false, resolved.displayPath(), "", "EPUBCheck probe interrupted", checkedAt);
        } catch (RuntimeException failure) {
            return new ConverterToolStatus("epubcheck", false, resolved.displayPath(), "",
                    failure.getClass().getSimpleName() + ": " + safe(failure.getMessage()), checkedAt);
        }
    }

    private boolean looksLikeEpubCheck(String output) {
        return output != null && output.toLowerCase(java.util.Locale.ROOT).contains("epubcheck");
    }

    private String diagnostic(ProcessExecutionSupport.Result result) {
        String stderr = result.stderrText().strip();
        String stdout = result.stdoutText().strip();
        String value = stdout.isBlank() ? stderr : stdout;
        if (value.length() > 2000) value = value.substring(0, 2000) + "…";
        return value;
    }

    private String firstLine(String value) {
        if (value == null || value.isBlank()) return "";
        int newline = value.indexOf('\n');
        return (newline < 0 ? value : value.substring(0, newline)).strip();
    }

    private String safe(String value) { return value == null || value.isBlank() ? "EPUBCheck unavailable" : value; }
}
