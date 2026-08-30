package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Single source of truth for the physical SQLite path of a collection. */
public final class CollectionDatabasePathResolver {
    private CollectionDatabasePathResolver() { }

    public static Path resolve(Collection collection) {
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) {
            throw new IllegalArgumentException("Collection/id must be specified");
        }
        String dbFile = collection.getDbFile();
        if (dbFile == null || dbFile.isBlank()) {
            return AppPaths.librariesDir().resolve(collection.getId() + ".db").toAbsolutePath().normalize();
        }
        return Paths.get(dbFile).toAbsolutePath().normalize();
    }
}
