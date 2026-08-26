package com.myhomelibcorp.application.action;

import java.util.List;

/** Non-executing preview of the exact argv/working-directory plan. */
public record BookActionPreview(List<PreviewCommand> commands) {
    public BookActionPreview { commands = commands == null ? List.of() : List.copyOf(commands); }
    public record PreviewCommand(List<String> argv, String workingDirectory, boolean waitForExit) {
        public PreviewCommand { argv = argv == null ? List.of() : List.copyOf(argv); }
    }
}
