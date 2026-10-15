package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for volumes that reject Files.createLink (Windows ERROR_INVALID_FUNCTION). */
class SafeFilePrimitivesPublicationFallbackTest {
    @TempDir Path temp;

    @Test
    void russianWindowsIncorrectFunctionFallsBackToFreshCopy() throws Exception {
        Path pending = Files.writeString(temp.resolve("pending"), "EPUB BYTES");
        Path target = temp.resolve("result.epub");
        boolean hardLinked = SafeFilePrimitives.publishFreshNoReplace(
                pending, target, "conversion", (link, existing) -> {
                    throw new FileSystemException(link.toString(), existing.toString(), "Неверная функция");
                });
        assertFalse(hardLinked);
        assertEquals("EPUB BYTES", Files.readString(target));
        assertEquals("EPUB BYTES", Files.readString(pending));
    }

    @Test
    void destinationCollisionPreservesOriginalAndStaging() throws Exception {
        Path pending = Files.writeString(temp.resolve("staged"), "NEW");
        Path target = Files.writeString(temp.resolve("existing"), "ORIGINAL");
        assertThrows(FileAlreadyExistsException.class,
                () -> SafeFilePrimitives.publishFreshNoReplace(pending, target, "organizer",
                        (link, existing) -> { throw new FileSystemException("Incorrect function"); }));
        assertEquals("ORIGINAL", Files.readString(target));
        assertEquals("NEW", Files.readString(pending));
    }

    @Test
    void permissionErrorsMustNotBeReinterpretedAsUnsupportedHardLinks() throws Exception {
        Path pending = Files.writeString(temp.resolve("staged"), "NEW");
        Path target = temp.resolve("newbook");
        assertThrows(FileSystemException.class,
                () -> SafeFilePrimitives.publishFreshNoReplace(pending, target, "organizer",
                        (link, existing) -> { throw new FileSystemException(link.toString(), null, "Access is denied"); }));
        assertFalse(Files.exists(target));
    }

    @Test
    void racingFallbackPublishersNeverOverwriteWinner() throws Exception {
        Path left = Files.writeString(temp.resolve("left"), "ONE");
        Path right = Files.writeString(temp.resolve("right"), "TWO");
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 16; round++) {
                Path target = temp.resolve("race-" + round);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                var one = pool.submit(() -> tryPublish(left, target, ready, go));
                var two = pool.submit(() -> tryPublish(right, target, ready, go));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                boolean oneWon = one.get(5, TimeUnit.SECONDS);
                boolean twoWon = two.get(5, TimeUnit.SECONDS);
                assertNotEquals(oneWon, twoWon);
                assertEquals(oneWon ? "ONE" : "TWO", Files.readString(target));
            }
        }
    }

    private static boolean tryPublish(Path source, Path target, CountDownLatch ready,
                                      CountDownLatch go) throws Exception {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout");
        try {
            SafeFilePrimitives.publishFreshNoReplace(source, target, "test", (link, existing) -> {
                throw new FileSystemException(link.toString(), existing.toString(), "Неверная функция");
            });
            return true;
        } catch (FileAlreadyExistsException expected) {
            return false;
        }
    }
}
