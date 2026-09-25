package com.myhomelibcorp.application.action;

import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.ExecutablePathSafety;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Builds and optionally executes safe ProcessBuilder plans; never invokes a shell. */
@Component
public class BookActionExecutionService {
    private static final long WAIT_TIMEOUT_MINUTES = 30;

    public BookActionPreview preview(BookActionProfile profile, Map<String, String> placeholders) {
        if (profile == null) return new BookActionPreview(List.of());
        List<BookActionPreview.PreviewCommand> commands = new ArrayList<>();
        for (BookActionCommand command : profile.commands()) {
            List<String> argv = argv(command, placeholders);
            String cwd = CommandTemplate.expandToken(command.workingDirectory(), placeholders).trim();
            commands.add(new BookActionPreview.PreviewCommand(argv, cwd, command.waitForExit()));
        }
        return new BookActionPreview(commands);
    }

    public BookActionRunResult execute(BookActionProfile profile, Map<String, String> placeholders) {
        return execute(profile, placeholders, ignored -> { });
    }

    /**
     * Executes a profile and exposes detached process handles to the caller so temporary materializations can be
     * retained until those processes exit. The callback runs immediately after ProcessBuilder.start().
     */
    public BookActionRunResult execute(BookActionProfile profile,
                                       Map<String, String> placeholders,
                                       Consumer<Process> detachedProcessObserver) {
        if (profile == null || !profile.enabled()) return BookActionRunResult.failure(0, "Профіль вимкнений або не знайдений");
        Consumer<Process> observer = detachedProcessObserver == null ? ignored -> { } : detachedProcessObserver;
        int started = 0;
        try {
            for (BookActionCommand command : profile.commands()) {
                List<String> argv = argv(command, placeholders);
                if (argv.isEmpty() || argv.getFirst().isBlank()) throw new IllegalArgumentException("Executable не задано");
                argv = validateExplicitExecutable(argv);
                ProcessBuilder builder = new ProcessBuilder(argv)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                String cwd = CommandTemplate.expandToken(command.workingDirectory(), placeholders).trim();
                if (!cwd.isBlank()) {
                    Path dir = Path.of(cwd).toAbsolutePath().normalize();
                    dir = SafeFilePublication.requireSafeDirectoryPath(dir, "Book action working directory");
                    builder.directory(dir.toFile());
                }
                Process process = builder.start();
                started++;
                if (command.waitForExit()) {
                    try {
                        if (!process.waitFor(WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                            ProcessExecutionSupport.terminate(process);
                            return BookActionRunResult.failure(started, "Команда перевищила ліміт очікування 30 хвилин");
                        }
                    } catch (InterruptedException interrupted) {
                        // Never orphan a child process when the owning action worker is cancelled/interrupted.
                        ProcessExecutionSupport.terminate(process);
                        Thread.currentThread().interrupt();
                        return BookActionRunResult.failure(started, "Виконання команди перервано");
                    }
                    if (process.exitValue() != 0) {
                        return BookActionRunResult.failure(started, "Команда завершилась з кодом " + process.exitValue());
                    }
                } else {
                    try {
                        observer.accept(process);
                    } catch (RuntimeException observerFailure) {
                        // start() already succeeded, therefore an observer failure must not leave an
                        // unowned detached process running after execute() reports failure.
                        ProcessExecutionSupport.terminate(process);
                        throw observerFailure;
                    }
                }
            }
            return BookActionRunResult.success(started);
        } catch (Exception e) {
            return BookActionRunResult.failure(started, e.getMessage());
        }
    }

    private List<String> validateExplicitExecutable(List<String> argv) throws Exception {
        Path safeExecutable = ExecutablePathSafety.resolve(argv.getFirst(), "Book action executable");
        List<String> safeArgv = new ArrayList<>(argv);
        safeArgv.set(0, safeExecutable.toString());
        return List.copyOf(safeArgv);
    }

    private List<String> argv(BookActionCommand command, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>();
        result.add(CommandTemplate.expandToken(command.executable(), placeholders));
        result.addAll(CommandTemplate.expand(command.arguments(), placeholders));
        return List.copyOf(result);
    }
}
