package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-platform path and filename regressions for imports and organizer exports. */
class SafeFileNameSupportRegressionTest {
    @TempDir Path temp;

    @Test void validNestedRelativePathRemainsUnchanged() {
        assertEquals(Path.of("shelf", "book.fb2"),
                SafeFileNameSupport.validateRelativePath(Path.of("shelf", "book.fb2")));
    }

    @Test void rejectsLeadingParentTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> SafeFileNameSupport.validateRelativePath(Path.of("..", "outside")));
    }

    @Test void rejectsEmbeddedParentTraversalEvenIfNormalizationWouldHideIt() {
        assertThrows(IllegalArgumentException.class,
                () -> SafeFileNameSupport.validateRelativePath(Path.of("shelf", "..", "book.fb2")));
    }

    @Test void rejectsDotComponentsEvenIfNormalizationWouldHideThem() {
        assertThrows(IllegalArgumentException.class,
                () -> SafeFileNameSupport.validateRelativePath(Path.of("shelf", ".", "book.fb2")));
    }

    @Test void rejectsAbsoluteTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> SafeFileNameSupport.validateRelativePath(temp.resolve("book.fb2")));
    }

    @Test void prefixesWindowsCoreDeviceNames() {
        for (String device : new String[] {"CON", "PRN", "AUX", "NUL"}) {
            assertEquals("_" + device, SafeFileNameSupport.sanitizeComponent(device, 240));
            assertEquals("_" + device + ".fb2",
                    SafeFileNameSupport.sanitizeComponent(device + ".fb2", 240));
        }
    }

    @Test void prefixesWindowsComAndLptDeviceNames() {
        for (String device : new String[] {"COM1", "COM9", "LPT1", "LPT9"}) {
            assertEquals("_" + device + ".zip",
                    SafeFileNameSupport.sanitizeComponent(device + ".zip", 240));
        }
    }

    @Test void prefixesWindowsSuperscriptComAndLptDeviceNames() {
        for (String device : new String[] {"COM¹", "LPT²", "COM³"}) {
            assertEquals("_" + device + ".epub",
                    SafeFileNameSupport.sanitizeComponent(device + ".epub", 240));
        }
    }

    @Test void prefixesReservedDeviceStemsWithTrailingSpaceBeforeExtension() {
        assertEquals("_CON .txt", SafeFileNameSupport.sanitizeComponent("CON .txt", 240));
        assertEquals("_COM1 .fb2", SafeFileNameSupport.sanitizeComponent("COM1 .fb2", 240));
    }

    @Test void leavesNonReservedDeviceLikeNamesAlone() {
        assertEquals("COM0.fb2", SafeFileNameSupport.sanitizeComponent("COM0.fb2", 240));
        assertEquals("LPT0.zip", SafeFileNameSupport.sanitizeComponent("LPT0.zip", 240));
        assertEquals("COM10.pdf", SafeFileNameSupport.sanitizeComponent("COM10.pdf", 240));
    }

    @Test void replacesSeparatorsAndControlCharacters() {
        assertEquals("a_b_c_d", SafeFileNameSupport.sanitizeComponent("a\\b/c\u0000d", 240));
    }

    @Test void normalizesUnicodeToNfc() {
        assertEquals("á", SafeFileNameSupport.sanitizeComponent("a\u0301", 240));
    }

    @Test void returnsUnoccupiedDestinationUnchanged() {
        Path unused = temp.resolve("book.fb2");
        assertEquals(unused, SafeFileNameSupport.resolveCollision(unused, "copy"));
    }

    @Test void firstCollisionKeepsExtensionAndOriginalBytes() throws IOException {
        Path original = Files.writeString(temp.resolve("book.fb2"), "original");
        assertEquals("book [copy].fb2",
                SafeFileNameSupport.resolveCollision(original, "copy").getFileName().toString());
        assertEquals("original", Files.readString(original));
    }

    @Test void repeatedCollisionAllocatesNumberedSuffix() throws IOException {
        Path original = Files.writeString(temp.resolve("book.fb2"), "original");
        Files.writeString(temp.resolve("book [copy].fb2"), "another");
        assertEquals("book [copy-2].fb2",
                SafeFileNameSupport.resolveCollision(original, "copy").getFileName().toString());
        assertEquals("another", Files.readString(temp.resolve("book [copy].fb2")));
    }

    @Test void longAsciiNameStaysWithinUtf8ByteBudgetAfterCollision() throws IOException {
        Path original = Files.writeString(temp.resolve("a".repeat(239) + ".zip"), "original");
        Path candidate = SafeFileNameSupport.resolveCollision(original, "copy");
        assertTrue(candidate.getFileName().toString().getBytes(StandardCharsets.UTF_8).length <= 240);
        assertTrue(candidate.getFileName().toString().endsWith(" [copy].zip"));
        assertEquals("original", Files.readString(original));
    }

    @Test void longUnicodeNameKeepsWholeCodepointsExtensionAndSuffix() throws IOException {
        Path original = Files.writeString(temp.resolve("ї".repeat(100) + ".fb2"), "original");
        Path candidate = SafeFileNameSupport.resolveCollision(original, "дублікат");
        String name = candidate.getFileName().toString();
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 240);
        assertTrue(name.endsWith(" [дублікат].fb2"));
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(name));
    }

    @Test void existingSymbolicLinkIsNotFollowedOrOverwritten() throws IOException {
        Path original = Files.writeString(temp.resolve("owned.fb2"), "outside");
        Path link = temp.resolve("book.fb2");
        try {
            Files.createSymbolicLink(link, original);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symlink creation unavailable on runner: " + unavailable);
        }
        assertNotEquals(link, SafeFileNameSupport.resolveCollision(link, "copy"));
        assertEquals("outside", Files.readString(original));
    }
}
