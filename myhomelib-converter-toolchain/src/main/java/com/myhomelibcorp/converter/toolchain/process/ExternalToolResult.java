package com.myhomelibcorp.converter.toolchain.process;

public record ExternalToolResult(int exitCode,
                                 String stdout,
                                 String stderr,
                                 boolean stdoutTruncated,
                                 boolean stderrTruncated,
                                 long durationMs) {
    public String mergedOutput() {
        if (stdout == null || stdout.isBlank()) return stderr == null ? "" : stderr;
        if (stderr == null || stderr.isBlank()) return stdout;
        return stdout + System.lineSeparator() + stderr;
    }
}
