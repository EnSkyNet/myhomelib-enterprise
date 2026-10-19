package com.myhomelibcorp.ui.organizer;

import com.myhomelibcorp.organizer.analysis.AuthorFolderOrderPreview;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorFolderRepairSelectionTest {
    private static AuthorFolderOrderPreview.Result proposal(String source, String target) {
        return new AuthorFolderOrderPreview.Result(source, target, 2,
                AuthorFolderOrderPreview.Status.PROPOSAL);
    }

    @Test void writesExactUtf8CompatibleSourceNamesWithoutCommands() {
        assertThat(AuthorFolderRepairSelection.render(List.of(
                proposal("Алёна Васильева", "Васильева Алёна"),
                proposal("Глеб Алёхин", "Алёхин Глеб"))))
                .isEqualTo("Алёна Васильева\nГлеб Алёхин\n");
    }

    @Test void rejectsUnsafeOrAmbiguousItems() {
        for (String name : List.of("../bad", "author\n--apply", "C:\\bad", " trailing ", "name.", "#CommentLikeAuthor", "")) {
            assertThatThrownBy(() -> AuthorFolderRepairSelection.render(List.of(proposal(name, "valid"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AuthorFolderRepairSelection.render(List.of(
                new AuthorFolderOrderPreview.Result("Ім’я Прізвище", "Прізвище Ім’я", 1,
                        AuthorFolderOrderPreview.Status.AMBIGUOUS))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorFolderRepairSelection.render(List.of(
                new AuthorFolderOrderPreview.Result("Ім’я Прізвище", "Прізвище Ім’я", 0,
                        AuthorFolderOrderPreview.Status.PROPOSAL))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsDuplicatesOrTooManySelections() {
        var items = new ArrayList<AuthorFolderOrderPreview.Result>();
        for (int i = 0; i < 11; i++) items.add(proposal("name" + i, "surname" + i));
        assertThatThrownBy(() -> AuthorFolderRepairSelection.render(items))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorFolderRepairSelection.render(List.of(
                proposal("Same Author", "Author Same"), proposal("same author", "Author same"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorFolderRepairSelection.render(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
