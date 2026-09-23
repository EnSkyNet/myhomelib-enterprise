package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import com.myhomelibcorp.application.util.SafeFilePublication;

import java.io.IOException;
import java.nio.file.Path;

public class AttachHlc2CollectionUseCase {
    private final LegacyCollectionAttachPort port;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for focused tests. Production wiring supplies the shared coordinator. */
    public AttachHlc2CollectionUseCase(LegacyCollectionAttachPort port) {
        this(port, new LibraryOperationCoordinator());
    }

    public AttachHlc2CollectionUseCase(LegacyCollectionAttachPort port,
                                       LibraryOperationCoordinator operationCoordinator) {
        this.port = java.util.Objects.requireNonNull(port, "port");
        this.operationCoordinator = java.util.Objects.requireNonNull(operationCoordinator, "operationCoordinator");
    }

    public LegacyCollectionAttachPort.AttachResult execute(Path file, String name, Path root) {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.CREATE)) {
            Path source = requireSafeSource(file);
            String lower = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(".hlc2") && !lower.endsWith(".db") && !lower.endsWith(".sqlite")) {
                throw new IllegalArgumentException("Очікується файл .hlc2/.db/.sqlite");
            }
            String effectiveName = name == null || name.isBlank()
                    ? stripExtension(source.getFileName().toString()) : name.trim();
            Path requestedRoot = root != null ? root.toAbsolutePath().normalize() : source.getParent();
            if (requestedRoot == null) requestedRoot = Path.of(".").toAbsolutePath().normalize();
            Path effectiveRoot = requireSafeRoot(requestedRoot);
            return port.attach(source, effectiveName, effectiveRoot);
        }
    }

    private static Path requireSafeSource(Path file) {
        if (file == null) throw new IllegalArgumentException("Файл колекції не знайдено");
        try {
            return SafeFilePublication.requireSafeRegularFile(
                    file.toAbsolutePath().normalize(), "collection attach source");
        } catch (IOException unsafeSource) {
            throw new IllegalArgumentException("Файл колекції не знайдено або шлях небезпечний", unsafeSource);
        }
    }

    private static Path requireSafeRoot(Path root) {
        try {
            return SafeFilePublication.requireSafeDirectoryPath(root, "attached collection root");
        } catch (IOException unsafeRoot) {
            throw new IllegalArgumentException("Коренева папка колекції недоступна або небезпечна: " + root, unsafeRoot);
        }
    }

    private static String stripExtension(String s) {
        int i = s.lastIndexOf('.');
        return i > 0 ? s.substring(0, i) : s;
    }
}
