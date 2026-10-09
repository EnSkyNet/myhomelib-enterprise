package com.myhomelibcorp.organizer.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizerTargetPublisherTest {
    @TempDir Path temp;

    @Test
    void publishesVerifiedStagedBytesToFreshTarget() throws Exception {
        Path staged = Files.writeString(temp.resolve("staged.fb2"), "verified payload", StandardCharsets.UTF_8);
        Path target = temp.resolve("library/Author/Book.fb2");

        new OrganizerTargetPublisher().publish(staged, target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("verified payload");
        assertThat(staged).exists();
        try (var children = Files.list(target.getParent())) {
            assertThat(children.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.contains(".mhl-tmp-"));
        }
    }

    @Test
    void refusesExistingTargetWithoutChangingEitherFile() throws Exception {
        Path staged = Files.writeString(temp.resolve("staged.fb2"), "staged", StandardCharsets.UTF_8);
        Path target = temp.resolve("existing.fb2");
        Files.writeString(target, "foreign", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new OrganizerTargetPublisher().publish(staged, target))
                .isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(staged, StandardCharsets.UTF_8)).isEqualTo("staged");
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("foreign");
    }
}
