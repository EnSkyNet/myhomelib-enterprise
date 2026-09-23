package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prevents JavaFX's English built-in button labels from reappearing in programmatic dialogs.
 * Every Dialog/Alert/ChoiceDialog/TextInputDialog must be passed through DialogLocalization.
 */
class DialogLocalizationCoverageContractTest {
    private static final Pattern CONSTRUCTOR = Pattern.compile(
            "(?:(\\b[A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*)?new\\s+(?:Alert|Dialog|ChoiceDialog|TextInputDialog)\\b");

    @Test
    void everyProgrammaticDialogIsPreparedForLocalization() throws Exception {
        List<String> leaks = new ArrayList<>();
        scan(Path.of("src/main/java"), leaks);
        Path bootstrap = Path.of("../myhomelib-bootstrap/src/main/java");
        if (Files.isDirectory(bootstrap)) scan(bootstrap, leaks);

        assertThat(leaks)
                .as("programmatic JavaFX dialogs without DialogLocalization.prepare(...) — these can expose English OK/Cancel/Yes/No")
                .isEmpty();
    }

    private static void scan(Path root, List<String> leaks) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                Matcher matcher = CONSTRUCTOR.matcher(source);
                while (matcher.find()) {
                    int line = 1 + (int) source.substring(0, matcher.start()).chars().filter(ch -> ch == '\n').count();
                    String before = source.substring(Math.max(0, matcher.start() - 120), matcher.start());
                    boolean prepared = before.contains("DialogLocalization.prepare(");
                    String variable = matcher.group(1);
                    if (!prepared && variable != null) {
                        String tail = source.substring(matcher.end());
                        Pattern prepare = Pattern.compile("DialogLocalization\\.prepare\\(\\s*" + Pattern.quote(variable) + "\\s*\\)");
                        Matcher prepareMatcher = prepare.matcher(tail);
                        Matcher reassignment = Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*=\\s*new\\s+").matcher(tail);
                        boolean hasPrepare = prepareMatcher.find();
                        boolean hasReassignment = reassignment.find();
                        prepared = hasPrepare && (!hasReassignment || prepareMatcher.start() < reassignment.start());
                    }
                    if (!prepared) leaks.add(path + ":" + line);
                }
            }
        }
    }
}
