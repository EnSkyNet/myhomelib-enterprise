package com.myhomelibcorp.application.action;

/** Persisted user override for one registered desktop command. */
public record ActionPreference(String shortcut, boolean visible) {
    public ActionPreference {
        shortcut = shortcut == null ? "" : shortcut.trim();
    }
}
