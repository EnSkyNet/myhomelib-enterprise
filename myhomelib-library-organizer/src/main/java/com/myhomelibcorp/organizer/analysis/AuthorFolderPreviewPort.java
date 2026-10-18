package com.myhomelibcorp.organizer.analysis;

import java.nio.file.Path;

/** A read-only catalog query; implementations belong to the persistence layer. */
public interface AuthorFolderPreviewPort {
    AuthorFolderOrderPreview.Result inspect(Path database, Path root, String folderName) throws Exception;

    /** Read-only bulk inspection; no folder rename or catalog write is permitted. */
    java.util.List<AuthorFolderOrderPreview.Result> inspectAll(Path database, Path root) throws Exception;
}
