package com.myhomelibcorp.infrastructure.epub;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.epub.optimizer.acceptance.EpubAcceptanceValidator;
import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Component
public final class EpubCheckAcceptanceValidator implements EpubAcceptanceValidator {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private final ApplicationSettingsPort settings;
    private final EpubCheckToolCommandFactory commands;

    public EpubCheckAcceptanceValidator(ApplicationSettingsPort settings, EpubCheckToolCommandFactory commands) {
        this.settings = settings;
        this.commands = commands;
    }

    @Override public Kind kind() { return Kind.EPUBCHECK; }
    @Override public String id() { return "epubcheck"; }

    @Override
    public Result validate(Path epub) {
        if (!safeRegularFilePath(epub)) return Result.failed("EPUBCheck input is not a safe regular file");
        String explicit = settings == null ? "" : settings.get("converter.epubcheck.executable", "");
        List<String> command = commands.validationCommand(explicit, epub);
        if (command.isEmpty()) return Result.unavailable("EPUBCheck JAR or compatible external launcher was not found");
        try {
            ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(command, null, TIMEOUT, 512 * 1024);
            String diagnostics = diagnostics(result);
            if (result.exitCode() == 0) return Result.passed(diagnostics.isBlank() ? "EPUBCheck passed" : diagnostics);
            return Result.failed(diagnostics.isBlank() ? "EPUBCheck exit code " + result.exitCode() : diagnostics);
        } catch (IOException failure) {
            return Result.unavailable(failure.getMessage());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Result.failed("EPUBCheck interrupted");
        } catch (RuntimeException failure) {
            return Result.failed(failure.getClass().getSimpleName() + ": " + safe(failure.getMessage()));
        }
    }

    private boolean safeRegularFilePath(Path file) {
        if (file == null) return false;
        try { EpubPathSafety.requireSafeRegularFile(file, "EPUBCheck input"); return true; }
        catch (Exception ignored) { return false; }
    }

    private String diagnostics(ProcessExecutionSupport.Result result) {
        String stderr = result.stderrText().strip();
        String stdout = result.stdoutText().strip();
        String value = stderr.isBlank() ? stdout : stderr;
        if (value.length() > 2000) value = value.substring(0, 2000) + "…";
        return value;
    }

    private String safe(String value) { return value == null ? "unknown EPUBCheck failure" : value; }
}
