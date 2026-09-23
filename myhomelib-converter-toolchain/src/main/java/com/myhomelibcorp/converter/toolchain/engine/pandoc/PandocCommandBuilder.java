package com.myhomelibcorp.converter.toolchain.engine.pandoc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PandocCommandBuilder {
    public List<String> build(Path executable, Path source, Path target, String sourceFormat, String targetFormat) {
        List<String> argv = new ArrayList<>();
        argv.add(executable.toString());
        argv.add("--sandbox");
        argv.add("--from");
        argv.add(reader(sourceFormat));
        argv.add("--to");
        argv.add(writer(targetFormat));
        argv.add("--output");
        argv.add(target.toString());
        argv.add(source.toString());
        argv.add("+RTS");
        argv.add("-M512M");
        argv.add("-RTS");
        return List.copyOf(argv);
    }

    private String reader(String format) {
        return switch (format) {
            case "epub" -> "epub";
            case "docx" -> "docx";
            case "rtf" -> "rtf";
            case "html", "htm" -> "html";
            case "txt" -> "markdown";
            case "md", "markdown" -> "markdown";
            case "odt" -> "odt";
            default -> throw new IllegalArgumentException("Unsupported Pandoc source: " + format);
        };
    }

    private String writer(String format) {
        return switch (format) {
            case "epub" -> "epub3";
            case "fb2" -> "fb2";
            case "docx" -> "docx";
            case "rtf" -> "rtf";
            case "txt" -> "plain";
            default -> throw new IllegalArgumentException("Unsupported Pandoc target: " + format);
        };
    }
}
