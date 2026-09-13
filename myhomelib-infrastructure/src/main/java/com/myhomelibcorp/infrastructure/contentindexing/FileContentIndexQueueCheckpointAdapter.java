package com.myhomelibcorp.infrastructure.contentindexing;

import com.myhomelibcorp.application.content.indexing.ContentIndexQueueCheckpoint;
import com.myhomelibcorp.application.content.indexing.ContentIndexingPriority;
import com.myhomelibcorp.application.content.indexing.ContentIndexingTask;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexQueueCheckpointPort;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@Component
public class FileContentIndexQueueCheckpointAdapter implements ContentIndexQueueCheckpointPort {
    private static final String VERSION = "1";

    @Override
    public synchronized Optional<ContentIndexQueueCheckpoint> load(String collectionId) {
        Path file = AppPaths.contentIndexQueueStateFile(collectionId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            if (!VERSION.equals(properties.getProperty("version"))) return Optional.empty();
            int count = Integer.parseInt(properties.getProperty("task.count", "0"));
            List<ContentIndexingTask> tasks = new ArrayList<>(Math.max(0, count));
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
                    Boolean.parseBoolean(properties.getProperty("paused", "false")), tasks));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load content-index queue checkpoint: " + file, failure);
        }
    }

    @Override
    public synchronized void save(ContentIndexQueueCheckpoint checkpoint) {
        Path file = AppPaths.contentIndexQueueStateFile(checkpoint.collectionId());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Properties properties = new Properties();
            properties.setProperty("version", VERSION);
            properties.setProperty("collectionId", checkpoint.collectionId());
            properties.setProperty("paused", Boolean.toString(checkpoint.paused()));
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
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "MyHomeLib content-index queue checkpoint");
            }
            AtomicFileSupport.moveReplacing(temp, file);
        } catch (Exception failure) {
            try { Files.deleteIfExists(temp); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Cannot save content-index queue checkpoint: " + file, failure);
        }
    }

    @Override
    public synchronized void clear(String collectionId) {
        try { Files.deleteIfExists(AppPaths.contentIndexQueueStateFile(collectionId)); }
        catch (Exception failure) { throw new IllegalStateException("Cannot clear content-index queue checkpoint", failure); }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing checkpoint property: " + key);
        return value;
    }
}
