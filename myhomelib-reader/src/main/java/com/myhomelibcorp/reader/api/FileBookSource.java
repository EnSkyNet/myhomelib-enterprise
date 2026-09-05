package com.myhomelibcorp.reader.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Locale;

public class FileBookSource implements BookSource {

    private final Path path;
    private final String id;

    public FileBookSource(Path path) {
        this.path = path;
        this.id = path.toAbsolutePath().toString();
    }

    public FileBookSource(Path path, String customId) {
        this.path = path;
        this.id = customId != null ? customId : path.toAbsolutePath().toString();
    }

    @Override
    public InputStream openStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public OptionalLong size() {
        try {
            return OptionalLong.of(Files.size(path));
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public String name() {
        return path.getFileName().toString();
    }

    @Override
    public String extension() {
        String name = name();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean exists() {
        return Files.exists(path) && Files.isReadable(path);
    }

    public Path getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "FileBookSource{" + path + "}";
    }
}
