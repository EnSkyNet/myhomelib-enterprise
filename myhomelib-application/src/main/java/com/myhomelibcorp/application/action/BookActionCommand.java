package com.myhomelibcorp.application.action;

/** One non-shell ProcessBuilder invocation inside a named book-action profile. */
public record BookActionCommand(
        String executable,
        String arguments,
        String workingDirectory,
        boolean waitForExit
) {
    public BookActionCommand {
        executable = executable == null ? "" : executable.trim();
        arguments = arguments == null ? "" : arguments.trim();
        workingDirectory = workingDirectory == null ? "" : workingDirectory.trim();
    }
}
