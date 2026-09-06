package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class BookResourceResolverDeletionSafetyTest {
    private static final List<BookId> IDS = List.of(BookId.fromString("11111111-1111-1111-1111-111111111111"));
    @TempDir Path temp;

    private final BookResourceResolver resolver = new BookResourceResolver(mock(ZipArchiveReader.class));

    @Test
    void stagedDeletionCanRollbackWithoutLosingBytes() throws Exception {
        Path root = Files.createDirectories(temp.resolve("managed"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "important book bytes");

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(book, root, "collection-test", IDS);

        assertThat(book).doesNotExist();
        assertThat(staged.recoveryPath()).exists();

        staged.rollback();

        assertThat(book).exists();
        assertThat(Files.readString(book)).isEqualTo("important book bytes");
        assertThat(staged.recoveryPath()).doesNotExist();
    }

    @Test
    void committedDeletionReleasesRecoveryBytes() throws Exception {
        Path root = Files.createDirectories(temp.resolve("managed-commit"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "bytes");

        BookResourcePort.StagedDeletion staged = resolver.stagePhysicalFileForDeletion(book, root, "collection-test", IDS);
        Path recovery = staged.recoveryPath();
        staged.commit();

        assertThat(book).doesNotExist();
        assertThat(recovery).doesNotExist();
    }

    @Test
    void absolutePathOutsideManagedRootIsRejected() throws Exception {
        Path root = Files.createDirectories(temp.resolve("managed-root"));
        Path outside = Files.createDirectories(temp.resolve("outside")).resolve("original.fb2");
        Files.writeString(outside, "do not delete");

        assertThatThrownBy(() -> resolver.stagePhysicalFileForDeletion(outside, root, "collection-test", IDS))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the managed root");

        assertThat(outside).exists();
        assertThat(Files.readString(outside)).isEqualTo("do not delete");
    }

    @Test
    void symlinkedParentCannotEscapeManagedRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("managed-symlink"));
        Path outside = Files.createDirectories(temp.resolve("outside-symlink"));
        Path outsideBook = outside.resolve("secret.fb2");
        Files.writeString(outsideBook, "secret");
        Path link = root.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            assumeTrue(false, "Symbolic links are unavailable on this test filesystem");
        }

        Path escaped = link.resolve("secret.fb2");
        assertThatThrownBy(() -> resolver.stagePhysicalFileForDeletion(escaped, root, "collection-test", IDS))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the managed root");
        assertThat(outsideBook).exists();
    }

    @Test
    void filesystemRootCannotBeDeclaredAsManagedDeletionRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("managed-root-check"));
        Path book = root.resolve("book.fb2");
        Files.writeString(book, "bytes");
        Path filesystemRoot = book.toAbsolutePath().getRoot();

        assertThatThrownBy(() -> resolver.stagePhysicalFileForDeletion(book, filesystemRoot, "collection-test", IDS))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Filesystem root");
        assertThat(book).exists();
    }
}
