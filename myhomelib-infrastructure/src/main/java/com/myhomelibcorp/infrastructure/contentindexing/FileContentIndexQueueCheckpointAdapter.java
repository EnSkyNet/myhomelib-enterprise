package com.myhomelibcorp.infrastructure.contentindexing;

import com.myhomelibcorp.application.content.indexing.ContentIndexQueueCheckpoint;
import com.myhomelibcorp.application.content.indexing.ContentIndexingPriority;
import com.myhomelibcorp.application.content.indexing.ContentIndexingTask;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexQueueCheckpointPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.infrastructure.util.LimitedInputStream;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@Component
public class FileContentIndexQueueCheckpointAdapter implements ContentIndexQueueCheckpointPort {
    private static final String VERSION = "2";
    private static final String LEGACY_VERSION = "1";
    private static final long MAX_CHECKPOINT_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_TASKS = 20_000;

    @Override
    public synchronized Optional<ContentIndexQueueCheckpoint> load(String collectionId) {
        Path file = AppPaths.contentIndexQueueStateFile(collectionId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Properties properties = new Properties();
        try {
            Path safe = SafeFilePublication.requireSafeRegularFile(file, "content-index queue checkpoint");
            try (InputStream raw = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS);
             InputStream in = new LimitedInputStream(raw, MAX_CHECKPOINT_BYTES)) {
                properties.load(in);
                String version = properties.getProperty("version");
                requireSupportedVersion(version, file);
                int count = Integer.parseInt(properties.getProperty("task.count", "0"));
                if (count < 0 || count > MAX_TASKS || count > Math.max(0, properties.size() / 7)) {
                    throw new IllegalArgumentException("Invalid content-index checkpoint task count: " + count);
                }
                List<ContentIndexingTask> tasks = new ArrayList<>(Math.min(count, 4096));
                for (int i = 0; i < count; i++) {
                    String prefix = "task." + i + ".";
                    tasks.add(new ContentIndexingTask(
                            required(properties, prefix + "id"), collectionId,
                            required(properties, prefix + "bookId"), required(properties, prefix + "artifactId"),
                            required(properties, prefix + "sourcePath"), required(properties, prefix + "format"),
                            ContentIndexingPriority.valueOf(properties.getProperty(prefix + "priority", "NORMAL")),
                            Instant.parse(required(properties, prefix + "enqueuedAt"))));
                }
                return Optional.of(new ContentIndexQueueCheckpoint(collectionId,
                        Boolean.parseBoolean(properties.getProperty("paused", "false")),
                        VERSION.equals(version) && Boolean.parseBoolean(properties.getProperty("rebuild.required", "false")),
                        tasks));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load content-index queue checkpoint: " + file, failure);
        }
    }

    @Override
    public synchronized void save(ContentIndexQueueCheckpoint checkpoint) {
        Path file = AppPaths.contentIndexQueueStateFile(checkpoint.collectionId());
        Path workspace = null;
        try {
            assertExistingCheckpointVersionSupported(file);
            String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(
                    file, "content-index queue checkpoint");
            workspace = SafeFilePublication.createPrivateWorkspace(
                    file.getParent(), ".content-index-checkpoint-work-", "content-index checkpoint workspace");
            Path temp = workspace.resolve("checkpoint.properties");
            Properties properties = new Properties();
            properties.setProperty("version", VERSION);
            properties.setProperty("collectionId", checkpoint.collectionId());
            properties.setProperty("paused", Boolean.toString(checkpoint.paused()));
            properties.setProperty("rebuild.required", Boolean.toString(checkpoint.rebuildRequired()));
            List<ContentIndexingTask> tasks = checkpoint.tasks().stream()
                    .sorted(Comparator.comparing(ContentIndexingTask::enqueuedAt).thenComparing(ContentIndexingTask::taskId))
                    .toList();
            properties.setProperty("task.count", Integer.toString(tasks.size()));
            for (int i = 0; i < tasks.size(); i++) {
                ContentIndexingTask task = tasks.get(i);
                String prefix = "task." + i + ".";
                properties.setProperty(prefix + "id", task.taskId());
                properties.setProperty(prefix + "bookId", task.bookId());
                properties.setProperty(prefix + "artifactId", task.artifactId());
                properties.setProperty(prefix + "sourcePath", task.sourcePath());
                properties.setProperty(prefix + "format", task.format());
                properties.setProperty(prefix + "priority", task.priority().name());
                properties.setProperty(prefix + "enqueuedAt", task.enqueuedAt().toString());
            }
            try (OutputStream out = SafeFilePublication.openFreshOutputStream(
                    temp, "staged content-index queue checkpoint")) {
                properties.store(out, "MyHomeLib content-index queue checkpoint");
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    temp, file, expectedTargetHash, "content-index queue checkpoint publication");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot save content-index queue checkpoint: " + file, failure);
        } finally {
            if (workspace != null) {
                try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
                catch (Exception ignored) { }
            }
        }
    }

    @Override
    public synchronized void clear(String collectionId) {
        Path file = AppPaths.contentIndexQueueStateFile(collectionId);
        try {
            String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(
                    file, "content-index queue checkpoint clear");
            if (expectedTargetHash != null) {
                SafeFilePublication.removeIfOwned(file, expectedTargetHash, "content-index queue checkpoint clear");
            }
        }
        catch (Exception failure) { throw new IllegalStateException("Cannot clear content-index queue checkpoint", failure); }
    }


    private static void assertExistingCheckpointVersionSupported(Path file) throws Exception {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        Properties properties = new Properties();
        Path safe = SafeFilePublication.requireSafeRegularFile(file, "content-index queue checkpoint");
        try (InputStream raw = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS);
             InputStream in = new LimitedInputStream(raw, MAX_CHECKPOINT_BYTES)) {
            properties.load(in);
        }
        requireSupportedVersion(properties.getProperty("version"), file);
    }

    private static void requireSupportedVersion(String version, Path file) {
        if (VERSION.equals(version) || LEGACY_VERSION.equals(version)) return;
        throw new IllegalStateException("Unsupported content-index queue checkpoint version '"
                + String.valueOf(version) + "': " + file);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing checkpoint property: " + key);
        return value;
    }
}
