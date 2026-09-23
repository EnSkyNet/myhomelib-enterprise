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

/** Guards against exposing Lombok/debug toString() values such as AuthorDto(...) or GroupDto(...) in JavaFX. */
class UserVisibleObjectRenderingContractTest {
    private static final Pattern DTO_CONTROL = Pattern.compile(
            "\\b(?:ListView|ComboBox|ChoiceBox)\\s*<\\s*([A-Za-z0-9_.$]*Dto)\\s*>\\s+(\\w+)\\b");

    @Test
    void knownDtoListsHaveExplicitUserFacingRenderers() throws Exception {
        String groups = source("com/myhomelibcorp/ui/group/GroupWorkspaceController.java");
        assertThat(groups).contains("groupsListView.setCellFactory", "item.getName()");

        String search = source("com/myhomelibcorp/ui/search/SearchWorkspaceController.java");
        assertThat(search).contains("authorsListView.setCellFactory", "authorDisplayName(item)");
    }

    @Test
    void everyDtoBackedListOrComboHasExplicitRenderer() throws Exception {
        Path root = Path.of("src/main/java");
        List<String> leaks = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String text = Files.readString(path);
                    Matcher matcher = DTO_CONTROL.matcher(text);
                    while (matcher.find()) {
                        String dto = matcher.group(1);
                        String variable = matcher.group(2);
                        boolean rendered = text.contains(variable + ".setCellFactory")
                                || text.contains(variable + ".setConverter")
                                || text.contains(variable + ".setButtonCell");
                        if (!rendered) leaks.add(path + ": " + variable + "<" + dto + ">");
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(leaks).as("DTO-backed JavaFX controls without explicit user-facing renderer").isEmpty();
    }

    @Test
    void genericChoiceDialogDoesNotSilentlyFallBackToArbitraryObjectToString() throws Exception {
        String service = source("com/myhomelibcorp/ui/service/DialogService.java");
        assertThat(service)
                .contains("DialogService::simpleChoiceLabel", "Object choices require an explicit user-facing labeler")
                .doesNotContain("value -> String.valueOf(value)");
    }

    @Test
    void groupDtoChoiceDialogsUseExplicitLabeler() throws Exception {
        String book = source("com/myhomelibcorp/ui/book/BookWorkspaceController.java");
        assertThat(book).contains("GroupDto::getName");

        String batch = source("com/myhomelibcorp/ui/controller/BatchOperationsController.java");
        assertThat(batch).contains("GroupDto::getName");
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java").resolve(relative));
    }
}
