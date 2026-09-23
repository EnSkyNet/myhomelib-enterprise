package com.myhomelibcorp.converter.toolchain.engine.libmobi;

import java.nio.file.Path;
import java.util.List;

public final class MobiToolCommandBuilder {
    public List<String> build(Path executable, Path source, Path outputDirectory) {
        return List.of(executable.toString(), "-e", "-o", outputDirectory.toString(), source.toString());
    }
}
