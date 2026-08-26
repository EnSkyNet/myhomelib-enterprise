package com.myhomelibcorp.ui.action;

/** Immutable built-in command metadata; runtime handler/context live in ActionRegistry. */
public record ActionDefinition(
        String id,
        String title,
        String defaultShortcut,
        boolean defaultVisible
) { }
