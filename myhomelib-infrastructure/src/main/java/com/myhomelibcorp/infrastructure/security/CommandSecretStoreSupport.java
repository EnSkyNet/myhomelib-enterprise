package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStoreException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        Process process = null;
        try {
            process = new ProcessBuilder(new ArrayList<>(command)).start();
            if (stdin != null) {
                process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new SecretStoreException("Native credential-store command timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return new Result(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            throw new SecretStoreException("Native credential-store command could not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretStoreException("Native credential-store command was interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    static boolean looksUnavailable(Result result) {
        String text = (result.stderr() + " " + result.stdout()).toLowerCase(Locale.ROOT);
        return text.contains("dbus") || text.contains("secret service") || text.contains("cannot autolaunch")
                || text.contains("no such file") || text.contains("not available");
    }

    record Result(int exitCode, String stdout, String stderr) {}
}
