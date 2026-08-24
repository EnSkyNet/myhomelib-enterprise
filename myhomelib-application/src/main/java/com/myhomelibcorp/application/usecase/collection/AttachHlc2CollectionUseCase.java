package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;

@RequiredArgsConstructor
public class AttachHlc2CollectionUseCase {
    private final LegacyCollectionAttachPort port;

    public LegacyCollectionAttachPort.AttachResult execute(Path file, String name, Path root) {
        if (file == null || !Files.isRegularFile(file)) throw new IllegalArgumentException("Файл колекції не знайдено");
        String lower = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".hlc2") && !lower.endsWith(".db") && !lower.endsWith(".sqlite")) {
            throw new IllegalArgumentException("Очікується файл .hlc2/.db/.sqlite");
        }
        String effectiveName = name == null || name.isBlank() ? stripExtension(file.getFileName().toString()) : name.trim();
        Path effectiveRoot = root != null ? root.toAbsolutePath().normalize()
                : (file.toAbsolutePath().getParent() == null ? Path.of(".").toAbsolutePath().normalize() : file.toAbsolutePath().getParent());
        return port.attach(file.toAbsolutePath().normalize(), effectiveName, effectiveRoot);
    }

    private static String stripExtension(String s) { int i=s.lastIndexOf('.'); return i>0?s.substring(0,i):s; }
}
