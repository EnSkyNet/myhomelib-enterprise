package com.myhomelibcorp.epub.optimizer.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpubZipTransformSupportTest {
    @TempDir Path tempDir;

    @Test
    void rewritesDeterministicallyExcludesEntriesAndFallsBackToOriginalContent() throws Exception {
        Path source = tempDir.resolve("source.epub");
        Path target = tempDir.resolve("target.epub");
        writeZip(source, List.of(
                new Item("mimetype", "application/epub+zip"),
                new Item("OPS/b.txt", "B"),
                new Item("META-INF/container.xml", "container"),
                new Item("OPS/a.txt", "A")));

        EpubZipTransformSupport.rewrite(
                source, target, "test target", Set.of("OPS/b.txt"), "cancelled",
                (zip, entry, output) -> {
                    if (entry.getName().equals("OPS/a.txt")) {
                        output.writeBytes("A2".getBytes(StandardCharsets.UTF_8));
                    }
                });

        try (ZipFile zip = new ZipFile(target.toFile(), StandardCharsets.UTF_8)) {
            List<String> names = zip.stream().map(ZipEntry::getName).toList();
            assertThat(names).containsExactly("mimetype", "META-INF/container.xml", "OPS/a.txt");
            assertThat(new String(zip.getInputStream(zip.getEntry("OPS/a.txt")).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("A2");
            assertThat(new String(zip.getInputStream(zip.getEntry("META-INF/container.xml")).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("container");
        }
    }

    @Test
    void rejectsTraversalEntryBeforeWritingOutput() throws Exception {
        Path source = tempDir.resolve("unsafe.epub");
        Path target = tempDir.resolve("target.epub");
        writeZip(source, List.of(
                new Item("mimetype", "application/epub+zip"),
                new Item("../escape.txt", "bad")));

        assertThatThrownBy(() -> EpubZipTransformSupport.rewrite(
                source, target, "test target", Set.of(), "cancelled",
                (zip, entry, output) -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe EPUB ZIP entry");
    }

    private static void writeZip(Path path, List<Item> items) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(java.nio.file.Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            for (Item item : new ArrayList<>(items)) {
                ZipEntry entry = new ZipEntry(item.name());
                out.putNextEntry(entry);
                out.write(item.value().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private record Item(String name, String value) { }
}
