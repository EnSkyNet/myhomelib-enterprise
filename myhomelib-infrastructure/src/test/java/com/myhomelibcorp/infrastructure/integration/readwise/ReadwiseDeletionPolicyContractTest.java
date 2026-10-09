package com.myhomelibcorp.infrastructure.integration.readwise;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReadwiseDeletionPolicyContractTest {
    @Test
    void deletingLocalAnnotationOnlyDropsLocalCheckpointAndDoesNotImplyRemoteDeletion() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V67__annotation_remote_export_state.sql"));
        assertThat(migration).contains("FOREIGN KEY (annotation_id)").contains("ON DELETE CASCADE");
        String port = Files.readString(Path.of("../myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/annotation/AnnotationRemoteExportPort.java"));
        assertThat(port).doesNotContain("delete").doesNotContain("remove");
    }
}
