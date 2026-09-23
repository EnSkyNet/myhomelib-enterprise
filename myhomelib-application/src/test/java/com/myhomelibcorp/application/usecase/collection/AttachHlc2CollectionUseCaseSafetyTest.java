package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AttachHlc2CollectionUseCaseSafetyTest {
    @TempDir Path temp;

    @Test
    void rejectsSourceReachedThroughSymlinkedParentWhenSupported() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("library.db"), "not opened");
        Path alias = temp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            return;
        }
        LegacyCollectionAttachPort port = mock(LegacyCollectionAttachPort.class);
        var useCase = new AttachHlc2CollectionUseCase(port, new LibraryOperationCoordinator());

        assertThrows(IllegalArgumentException.class,
                () -> useCase.execute(alias.resolve("library.db"), "Library", temp));

        verifyNoInteractions(port);
    }
}
