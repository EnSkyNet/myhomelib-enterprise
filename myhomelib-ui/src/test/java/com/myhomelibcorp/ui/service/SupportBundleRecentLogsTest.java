package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SupportBundleRecentLogsTest {
    @TempDir Path temp;

    @Test
    void selectsOnlyNewest128FilesFromHundredsWithoutKeepingEveryPath() throws Exception {
        for (int i = 0; i < 300; i++) {
            Path log = Files.writeString(temp.resolve("log-" + i + ".log"), "synthetic");
            Files.setLastModifiedTime(log, FileTime.fromMillis(1_700_000_000_000L + 1_000L * i));
        }
        Files.createDirectory(temp.resolve("fake.log"));

        var service = new SupportBundleService(mock(ApplicationSettingsPort.class));
        List<Path> files = service.newestLogFiles(temp);

        assertThat(files).hasSize(SupportBundleService.MAX_LOG_ENTRIES);
        assertThat(files.getFirst().getFileName().toString()).isEqualTo("log-299.log");
        assertThat(files.getLast().getFileName().toString()).isEqualTo("log-172.log");
        assertThat(files).allMatch(Files::isRegularFile);
        assertThat(files).doesNotContain(temp.resolve("log-171.log"), temp.resolve("fake.log"));
    }

    @Test
    void smallDirectoryRetainsAllFilesNewestFirst() throws Exception {
        Path older = Files.writeString(temp.resolve("old.log"), "old");
        Path newer = Files.writeString(temp.resolve("new.log"), "new");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_700_000_000_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(1_700_000_001_000L));

        var service = new SupportBundleService(mock(ApplicationSettingsPort.class));
        assertThat(service.newestLogFiles(temp)).containsExactly(newer, older);
    }
}
