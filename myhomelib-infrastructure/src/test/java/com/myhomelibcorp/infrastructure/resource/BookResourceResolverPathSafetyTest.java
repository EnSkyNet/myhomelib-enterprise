package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BookResourceResolverPathSafetyTest {

    @TempDir
    Path temp;

    private final BookResourceResolver resolver = new BookResourceResolver(new ZipArchiveReader());

    @Test
    void rejectsRelativeTraversalOutsideCollectionRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path outside = temp.resolve("outside.fb2");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);

        assertThat(resolver.buildFilePath(root.toString(), "..", "outside.fb2")).isNull();
        assertThat(resolver.locateBookFile("outside.fb2", "..", root.toString(), "")).isEmpty();
        assertThat(resolver.readBookData("outside.fb2", "..", root.toString(), "")).isEmpty();
    }

    @Test
    void rejectsAbsoluteFileOutsideCollectionRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path outside = temp.resolve("absolute-outside.fb2");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);

        assertThat(resolver.buildFilePath(root.toString(), "", outside.toString())).isNull();
        assertThat(resolver.locateBookFile(outside.toString(), "", root.toString(), "")).isEmpty();
    }

    @Test
    void keepsAbsoluteLocalArchivePathWhenItIsInsideCollectionRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path archive = root.resolve("bundle.zip");
        Files.writeString(archive, "placeholder", StandardCharsets.UTF_8);

        assertThat(resolver.buildFilePath(root.toString(), null, archive.toString()))
                .isEqualTo(archive.toAbsolutePath().normalize());
    }

    @Test
    void rejectsSymlinkDirectoryThatEscapesCollectionRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path secret = outside.resolve("secret.fb2");
        Files.writeString(secret, "secret", StandardCharsets.UTF_8);
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        assertThat(resolver.buildFilePath(root.toString(), "linked", "secret.fb2")).isNull();
        assertThat(resolver.locateBookFile("secret.fb2", "linked", root.toString(), "")).isEmpty();
    }

    @Test
    void rejectsBookReachedThroughSymlinkedParentEvenWhenTargetStaysInsideCollectionRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library-parent-link"));
        Path real = Files.createDirectories(root.resolve("real"));
        Path book = real.resolve("book.fb2");
        Files.writeString(book, "book", StandardCharsets.UTF_8);
        Path alias = root.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        assertThat(resolver.buildFilePath(root.toString(), "alias", "book.fb2")).isNull();
        assertThat(resolver.locateBookFile("book.fb2", "alias", root.toString(), "")).isEmpty();
        assertThat(resolver.readBookData("book.fb2", "alias", root.toString(), "")).isEmpty();
    }

    @Test
    void rejectsRootlessBookReachedThroughSymlinkedParent() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("rootless-real"));
        Path book = outside.resolve("book.fb2");
        Files.writeString(book, "book", StandardCharsets.UTF_8);
        Path alias = temp.resolve("rootless-alias");
        try {
            Files.createSymbolicLink(alias, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        assertThat(resolver.locateBookFile("book.fb2", alias.toString(), null, "")).isEmpty();
        assertThat(resolver.readBookData("book.fb2", alias.toString(), null, "")).isEmpty();
    }

    @Test
    void allowsNormalizedNestedPathThatRemainsInsideCollectionRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("library"));
        Path nested = Files.createDirectories(root.resolve("a")).resolve("book.fb2");
        Files.writeString(nested, "book", StandardCharsets.UTF_8);

        assertThat(resolver.buildFilePath(root.toString(), "a/./b/..", "book.fb2"))
                .isEqualTo(nested.toAbsolutePath().normalize());
        assertThat(resolver.locateBookFile("book.fb2", "a/./b/..", root.toString(), ""))
                .contains(nested.toAbsolutePath().normalize());
    }
}
