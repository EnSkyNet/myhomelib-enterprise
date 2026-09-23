package com.myhomelibcorp.reader.api;

import com.myhomelibcorp.shared.util.AtomicFileSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
        Path safe = AtomicFileSupport.requireSafeRegularFile(path, "Reader file source");
        return Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public OptionalLong size() {
        try {
            Path safe = AtomicFileSupport.requireSafeRegularFile(path, "Reader file source");
            BasicFileAttributes attrs = Files.readAttributes(safe, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isRegularFile() ? OptionalLong.of(attrs.size()) : OptionalLong.empty();
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
        try {
            Path safe = AtomicFileSupport.requireSafeRegularFile(path, "Reader file source");
            return Files.isReadable(safe);
        } catch (IOException e) {
            return false;
        }
    }

    public Path getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "FileBookSource{" + path + "}";
    }
}
