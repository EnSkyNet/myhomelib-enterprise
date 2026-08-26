package com.myhomelibcorp.application.action;

import java.util.List;

/** Named, ordered external action profile available from a book context menu. */
public record BookActionProfile(String id, String name, boolean enabled, List<BookActionCommand> commands) {
    public BookActionProfile {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        commands = commands == null ? List.of() : List.copyOf(commands);
    }
}
