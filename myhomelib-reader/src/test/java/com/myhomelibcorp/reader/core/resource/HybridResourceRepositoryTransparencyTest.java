package com.myhomelibcorp.reader.core.resource;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridResourceRepositoryTransparencyTest {

    @Test
    void missingResourceIsEmptyButLostBackingFileIsAnIoFailure() throws Exception {
        HybridResourceRepository repository = new HybridResourceRepository(0, 0);
        repository.add("image", "image/png", new byte[]{1, 2, 3, 4});

        assertThat(repository.open("missing")).isEmpty();

        Field directoryField = HybridResourceRepository.class.getDeclaredField("tempDirectory");
        directoryField.setAccessible(true);
        Path directory = (Path) directoryField.get(repository);
        try (var files = Files.list(directory)) {
            Path backingFile = files.findFirst().orElseThrow();
            Files.delete(backingFile);
        }

        assertThatThrownBy(() -> repository.open("image"))
                .isInstanceOf(UncheckedIOException.class);
        repository.close();
    }


    @Test
    void refusesSymlinkReplacementOfTempBackedResource() throws Exception {
        HybridResourceRepository repository = new HybridResourceRepository(0, 0);
        repository.add("image", "image/png", new byte[]{1, 2, 3, 4});

        Field directoryField = HybridResourceRepository.class.getDeclaredField("tempDirectory");
        directoryField.setAccessible(true);
        Path directory = (Path) directoryField.get(repository);
        Path backingFile;
        try (var files = Files.list(directory)) {
            backingFile = files.findFirst().orElseThrow();
        }
        Path outside = Files.write(tempDirSibling(directory), new byte[]{9, 9, 9});
        Files.delete(backingFile);
        try {
            Files.createSymbolicLink(backingFile, outside);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException unsupported) {
            repository.close();
            Assumptions.abort("Symbolic links are unavailable in this test environment");
        }

        assertThatThrownBy(() -> repository.open("image"))
                .isInstanceOf(UncheckedIOException.class);
        repository.close();
        Files.deleteIfExists(outside);
    }

    private static Path tempDirSibling(Path directory) {
        return directory.getParent().resolve("reader-outside-" + java.util.UUID.randomUUID() + ".bin");
    }

    @Test
    void largeStreamRemainsOutsideMemoryBudget() throws Exception {
        HybridResourceRepository repository = new HybridResourceRepository(8, 4);
        byte[] data = new byte[32 * 1024];
        assertThat(repository.add("large", "image/jpeg", InputStream.nullInputStream(), data.length)).isFalse();

        try (InputStream in = new java.io.ByteArrayInputStream(data)) {
            assertThat(repository.add("large", "image/jpeg", in, data.length)).isTrue();
        }
        assertThat(repository.inMemorySize()).isZero();
        assertThat(repository.open("large")).isPresent();
        repository.close();
    }
}
