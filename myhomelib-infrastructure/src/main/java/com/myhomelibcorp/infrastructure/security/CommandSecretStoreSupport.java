package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStoreException;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CommandSecretStoreSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private CommandSecretStoreSupport() {}

    static boolean isExecutableOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        for (String part : path.split(java.io.File.pathSeparator)) {
            try {
                Path candidate = Path.of(part).resolve(executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
            } catch (RuntimeException ignored) {
                // Ignore malformed PATH entries.
            }
        }
        return false;
    }

    static Result run(List<String> command, String stdin) {
        try {
            ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(
                    new ArrayList<>(command), stdin, TIMEOUT, 256 * 1024);
            return new Result(result.exitCode(), result.stdoutText().strip(), result.stderrText().strip());
        } catch (ProcessExecutionSupport.ProcessTimeoutException e) {
            throw new SecretStoreException("Native credential-store command timed out", e);
        } catch (IOException e) {
            throw new SecretStoreException("Native credential-store command could not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretStoreException("Native credential-store command was interrupted", e);
        }
    }

    static boolean looksUnavailable(Result result) {
        String text = (result.stderr() + " " + result.stdout()).toLowerCase(Locale.ROOT);
        return text.contains("dbus") || text.contains("secret service") || text.contains("cannot autolaunch")
                || text.contains("no such file") || text.contains("not available");
    }

    record Result(int exitCode, String stdout, String stderr) {}
}
