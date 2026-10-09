package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookFile;

import java.util.Objects;

/** Canonical comparison of the file projection used to match a BookFile to a concrete artifact. */
final class BookArtifactFileIdentity {
    private BookArtifactFileIdentity() {
    }

    static boolean matches(BookFile opened, BookArtifact artifact) {
        if (opened == null || artifact == null || artifact.getFile() == null) return false;
        BookFile candidate = artifact.getFile();
        return Objects.equals(normalize(opened.getFileName()), normalize(candidate.getFileName()))
                && Objects.equals(normalize(opened.getFolder()), normalize(candidate.getFolder()))
                && Objects.equals(normalize(opened.getCollectionRoot()), normalize(candidate.getCollectionRoot()))
                && Objects.equals(normalize(opened.getArchiveEntry()), normalize(candidate.getArchiveEntry()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }
}
