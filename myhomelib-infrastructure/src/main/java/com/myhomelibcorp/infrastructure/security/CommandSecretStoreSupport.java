package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStoreException;
import com.myhomelibcorp.shared.util.ExecutablePathSafety;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CommandSecretStoreSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private CommandSecretStoreSupport() {}

    static boolean isExecutableOnPath(String executable) {
        try {
            ExecutablePathSafety.resolve(executable, "Native credential-store executable");
            return true;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    static Result run(List<String> command, String stdin) {
        try {
            if (command == null || command.isEmpty()) {
                throw new SecretStoreException("Native credential-store command is empty");
            }
            ArrayList<String> safeCommand = new ArrayList<>(command);
            safeCommand.set(0, ExecutablePathSafety.resolve(
                    safeCommand.getFirst(), "Native credential-store executable").toString());
            ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(
                    safeCommand, stdin, TIMEOUT, 256 * 1024);
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
