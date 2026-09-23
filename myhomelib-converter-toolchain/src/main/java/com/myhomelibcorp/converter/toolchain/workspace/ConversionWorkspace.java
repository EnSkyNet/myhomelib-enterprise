package com.myhomelibcorp.converter.toolchain.workspace;

import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import com.myhomelibcorp.shared.util.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class ConversionWorkspace implements AutoCloseable {
    private final Path root;
    private final Path input;
    private final Path output;
    private final Path logs;

    private ConversionWorkspace(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.input = this.root.resolve("input");
        this.output = this.root.resolve("output");
        this.logs = this.root.resolve("logs");
        ConversionPathSafety.createDirectoriesNoLinks(input, "Conversion workspace");
        ConversionPathSafety.createDirectoriesNoLinks(output, "Conversion workspace");
        ConversionPathSafety.createDirectoriesNoLinks(logs, "Conversion workspace");
    }

    public static ConversionWorkspace create() throws IOException {
        Path parent = ConversionPathSafety.createDirectoriesNoLinks(
                AppPaths.cacheDir().resolve("conversion-workspaces"), "Conversion workspace root");
        return new ConversionWorkspace(parent.resolve(UUID.randomUUID().toString()));
    }

    public Path root() { return root; }
    public Path inputDir() { return input; }
    public Path outputDir() { return output; }
    public Path logsDir() { return logs; }

    public Path sourcePath(String extension) throws IOException {
        ConversionPathSafety.requireSafeDirectoryPath(input, "Conversion workspace input");
        return input.resolve("source-" + UUID.randomUUID() + safeExtension(extension)).normalize();
    }

    public Path outputPath(int stepIndex, String extension) {
        return output.resolve("step-" + stepIndex + safeExtension(extension)).normalize();
    }

    private static String safeExtension(String extension) {
        String ext = extension == null ? ".book" : extension.trim().toLowerCase();
        if (!ext.startsWith(".")) ext = "." + ext;
        if (!ext.matches("\\.[a-z0-9][a-z0-9._-]{0,20}")) return ".book";
        return ext;
    }

    @Override public void close() { WorkspaceCleaner.deleteRecursively(root); }
}
