package com.myhomelibcorp.startup;

import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Best-effort cleanup of unpublished backup staging snapshots left by an interrupted backup. */
@Component
@Slf4j
public class BackupStartupTask implements StartupTask {

    @Override public String id() { return "BackupStartupTask"; }
    @Override public StartupFailurePolicy failurePolicy() { return StartupFailurePolicy.BEST_EFFORT; }

    @Override
    public StartupTaskResult execute(StartupContext context) throws IOException {
        Path backups = AppPaths.backupsDir().toAbsolutePath().normalize();
        Files.createDirectories(backups);
        int removed = cleanupSnapshotTemps(backups);
        return removed == 0
                ? StartupTaskResult.skipped("no interrupted backup snapshot staging files")
                : StartupTaskResult.success("removed " + removed + " interrupted backup staging file(s)");
    }

    private int cleanupSnapshotTemps(Path backups) throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> root = Files.newDirectoryStream(backups)) {
            for (Path entry : root) {
                if (Files.isRegularFile(entry) && isSnapshotTemp(entry)) {
                    if (Files.deleteIfExists(entry)) removed++;
                    continue;
                }
                if (!Files.isDirectory(entry)) continue;
                try (DirectoryStream<Path> children = Files.newDirectoryStream(entry)) {
                    for (Path child : children) {
                        if (Files.isRegularFile(child) && isSnapshotTemp(child) && Files.deleteIfExists(child)) {
                            removed++;
                        }
                    }
                }
            }
        }
        if (removed > 0) log.warn("Removed {} interrupted backup snapshot staging file(s)", removed);
        return removed;
    }

    private static boolean isSnapshotTemp(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().endsWith(".snapshot.tmp");
    }
}
