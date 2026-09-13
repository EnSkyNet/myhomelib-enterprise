package com.myhomelibcorp.infrastructure.sync.folder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import com.myhomelibcorp.domain.model.sync.SyncSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFolderSyncAdapterIntegrationTest {
    @TempDir Path temp;

    @Test
    void twoInstancesConvergeThroughSharedFolderAndCursorFiltersIncrementally() throws Exception {
        LocalFolderSyncAdapter first = adapter();
        LocalFolderSyncAdapter second = adapter();
        ChangeSet a1 = set("a-1", "device-a", 1, "", "favorite:a", SyncEntityType.FAVORITE, "a", "true");
        ChangeSet b1 = set("b-1", "device-b", 1, "", "setting:b", SyncEntityType.SETTING, "b", "dark");
        ChangeSet a2 = set("a-2", "device-a", 2, "device-a:1", "rating:a", SyncEntityType.RATING, "a", "5");

        first.push(a1);
        second.push(b1);
        first.push(a2);

        assertThat(second.pull(Map.of())).containsExactly(a1, a2, b1);
        assertThat(first.pull(Map.of("device-a", 1L, "device-b", 1L))).containsExactly(a2);
        assertThat(Files.list(temp).filter(p -> p.getFileName().toString().endsWith(LocalFolderSyncAdapter.PART_SUFFIX)))
                .isEmpty();
    }

    @Test
    void partialFilesAreIgnoredAndFinalPublishIsImmutable() throws Exception {
        LocalFolderSyncAdapter adapter = adapter();
        Files.writeString(temp.resolve(".interrupted" + LocalFolderSyncAdapter.PART_SUFFIX), "partial");
        ChangeSet set = set("set-1", "device-a", 1, "", "progress:b", SyncEntityType.READING_PROGRESS, "b", "0.2");

        assertThat(adapter.pull(Map.of())).isEmpty();
        adapter.push(set);
        adapter.push(set); // idempotent replay

        assertThat(adapter.pull(Map.of())).containsExactly(set);
        long finals;
        try (var stream = Files.list(temp)) {
            finals = stream.filter(p -> p.getFileName().toString().endsWith(LocalFolderSyncAdapter.FINAL_SUFFIX)).count();
        }
        assertThat(finals).isEqualTo(1);
    }

    @Test
    void lockContentionFailsFastInsteadOfWritingConcurrentPartialBundle() throws Exception {
        LocalFolderSyncAdapter adapter = adapter();
        Files.createDirectories(temp);
        Path lockPath = temp.resolve(LocalFolderSyncAdapter.LOCK_FILE);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertThatThrownBy(() -> adapter.push(set("set", "device-a", 1, "", "setting:x",
                    SyncEntityType.SETTING, "x", "v")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("locked");
        }
    }

    @Test
    void sameDeviceSequenceWithDifferentChangeSetIsDetectedAsConflict() throws Exception {
        LocalFolderSyncAdapter adapter = adapter();
        ChangeSet first = set("set-a", "device-a", 1, "", "setting:x", SyncEntityType.SETTING, "x", "one");
        ChangeSet second = set("set-b", "device-a", 1, "", "setting:y", SyncEntityType.SETTING, "y", "two");
        adapter.push(first);
        adapter.push(second);

        assertThatThrownBy(() -> adapter.pull(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting sync bundles for cursor device-a:1");
    }

    private LocalFolderSyncAdapter adapter() {
        return new LocalFolderSyncAdapter(temp, new SyncBundleJsonCodec(new ObjectMapper()));
    }

    private static ChangeSet set(String id, String device, long sequence, String previous,
                                 String syncId, SyncEntityType type, String localKey, String value) {
        Instant now = Instant.parse("2026-09-12T12:00:00Z").plusSeconds(sequence);
        SyncRecord record = SyncRecord.live(syncId, type, localKey, sequence - 1, sequence, now, device,
                Map.of("value", value));
        return new ChangeSet(id, device, sequence, previous, now, SyncSchema.CURRENT_VERSION, List.of(record));
    }
}
