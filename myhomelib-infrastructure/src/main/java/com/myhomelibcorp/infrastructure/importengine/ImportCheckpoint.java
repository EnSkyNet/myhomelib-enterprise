package com.myhomelibcorp.infrastructure.importengine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

@Component
@Slf4j
public class ImportCheckpoint {

    private static final String CHECKPOINT_DIR = System.getProperty("user.home") + "/.myhomelibcorp/checkpoints/";

    public void saveCheckpoint(String importId, long processed, long total, long lastBookId, String fileName) {
        try {
            Path dir = Paths.get(CHECKPOINT_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            CheckpointData data = new CheckpointData(importId, processed, total, lastBookId, fileName, LocalDateTime.now());
            try (ObjectOutputStream out = new ObjectOutputStream(
                    new FileOutputStream(CHECKPOINT_DIR + importId + ".ckpt"))) {
                out.writeObject(data);
            }
            log.info("Checkpoint saved: {} books processed", processed);
        } catch (Exception e) {
            log.error("Failed to save checkpoint", e);
        }
    }

    public CheckpointData loadCheckpoint(String importId) {
        try (ObjectInputStream in = new ObjectInputStream(
                new FileInputStream(CHECKPOINT_DIR + importId + ".ckpt"))) {
            return (CheckpointData) in.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteCheckpoint(String importId) {
        try {
            Files.deleteIfExists(Paths.get(CHECKPOINT_DIR + importId + ".ckpt"));
        } catch (Exception e) {
            log.warn("Failed to delete checkpoint", e);
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CheckpointData implements Serializable {
        private String importId;
        private long processed;
        private long total;
        private long lastBookId;
        private String fileName;
        private LocalDateTime timestamp;
    }
}