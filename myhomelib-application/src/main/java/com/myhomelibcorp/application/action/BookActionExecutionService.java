package com.myhomelibcorp.application.action;

import com.myhomelibcorp.application.util.CommandTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        if (profile == null || !profile.enabled()) return BookActionRunResult.failure(0, "Профіль вимкнений або не знайдений");
        int started = 0;
        try {
            for (BookActionCommand command : profile.commands()) {
                List<String> argv = argv(command, placeholders);
                if (argv.isEmpty() || argv.getFirst().isBlank()) throw new IllegalArgumentException("Executable не задано");
                ProcessBuilder builder = new ProcessBuilder(argv)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                String cwd = CommandTemplate.expandToken(command.workingDirectory(), placeholders).trim();
                if (!cwd.isBlank()) {
                    Path dir = Path.of(cwd).toAbsolutePath().normalize();
                    if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Робоча папка не існує: " + dir);
                    builder.directory(dir.toFile());
                }
                Process process = builder.start();
                started++;
                if (command.waitForExit()) {
                    if (!process.waitFor(WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                        process.destroyForcibly();
                        return BookActionRunResult.failure(started, "Команда перевищила ліміт очікування 30 хвилин");
                    }
                    if (process.exitValue() != 0) {
                        return BookActionRunResult.failure(started, "Команда завершилась з кодом " + process.exitValue());
                    }
                }
            }
            return BookActionRunResult.success(started);
        } catch (Exception e) {
            return BookActionRunResult.failure(started, e.getMessage());
        }
    }

    private List<String> argv(BookActionCommand command, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>();
        result.add(CommandTemplate.expandToken(command.executable(), placeholders));
        result.addAll(CommandTemplate.expand(command.arguments(), placeholders));
        return List.copyOf(result);
    }
}
