package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BookDownloadCoordinatorLocalFirstContractTest {
    @Test
    void rechecksPhysicalLocalFileImmediatelyBeforeNetworkDownload() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java"),
                StandardCharsets.UTF_8);
        assertThat(source).contains("ONLINE_DOWNLOAD_SKIP_LOCAL");
        assertThat(source).contains("ONLINE_DOWNLOAD_SKIP_LOCAL_RACE");
        assertThat(source).contains("locateExistingLocal(effectiveBook, collection)");
        assertThat(source).contains("LegacyOnlineBookLocation.archivePath");
    }
}
