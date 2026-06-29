package com.myhomelibcorp.domain.model.valueobject;

import lombok.Value;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Value Object, що представляє фізичний файл книги.
 */
@Value
public class BookFile {
    String fileName;
    String folder;
    String archiveEntry;
    long fileSize;
    String collectionRoot;

    public String getFullPath() {
        if (folder == null || folder.isBlank()) {
            return fileName;
        }
        Path folderPath = Paths.get(folder);
        if (folderPath.isAbsolute()) {
            return folderPath.toString();
        }
        if (collectionRoot != null && !collectionRoot.isBlank()) {
            return Paths.get(collectionRoot, folder).toString();
        }
        return folder;
    }

    public boolean hasArchiveEntry() {
        return archiveEntry != null && !archiveEntry.isBlank();
    }

    public String getDisplayName() {
        return fileName != null && !fileName.isBlank() ? fileName : "unknown.fb2";
    }

    public String getArchiveEntryName() {
        return hasArchiveEntry() ? archiveEntry : fileName;
    }
}