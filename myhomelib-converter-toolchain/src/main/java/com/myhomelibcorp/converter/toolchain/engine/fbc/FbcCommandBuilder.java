package com.myhomelibcorp.converter.toolchain.engine.fbc;

import java.nio.file.Path;
import java.util.List;

public final class FbcCommandBuilder {
    public List<String> build(Path executable, Path source, Path target, String targetFormat) {
        String fbcTarget = switch (targetFormat) {
            case "epub" -> "epub3";
            case "kepub" -> "kepub";
            case "pdf" -> "pdf";
            case "txt" -> "txt";
            default -> throw new IllegalArgumentException("Unsupported fb2cng target: " + targetFormat);
        };
        return List.of(executable.toString(), "convert", "--to", fbcTarget,
                "--output-file", target.toString(), source.toString());
    }
}
