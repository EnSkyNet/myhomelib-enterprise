package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Finalizes a committed export before the application reports it as complete.
 *
 * <p>{@link ExportRequest.CompletionPolicy#EJECT_SAFE} requires a successful
 * file-level force so Java has handed data and file metadata to the filesystem
 * before success is reported. Parent-directory metadata is forced when the
 * host/filesystem exposes directory channels; that extra step is deliberately
 * best-effort because it is not portable across all Windows/device filesystems.</p>
 */
@Component
@Slf4j
public class ExportCompletionService {

    public void complete(Path targetFile, ExportRequest.CompletionPolicy policy) throws IOException {
        ExportRequest.CompletionPolicy effective = policy == null
                ? ExportRequest.CompletionPolicy.VERIFY_READABLE : policy;
        if (effective != ExportRequest.CompletionPolicy.EJECT_SAFE) return;

        try (FileChannel channel = FileChannel.open(targetFile, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        forceParentDirectoryBestEffort(targetFile == null ? null : targetFile.getParent());
    }

    private void forceParentDirectoryBestEffort(Path directory) {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException unsupported) {
            log.debug("Parent-directory durability flush is not available for {}: {}",
                    directory, unsupported.getMessage());
        }
    }
}
