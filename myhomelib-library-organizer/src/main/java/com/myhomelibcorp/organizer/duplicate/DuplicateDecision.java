package com.myhomelibcorp.organizer.duplicate;

import java.nio.file.Path;

/** Duplicate match with logical archive-entry identity when the matched book lives inside a container. */
public record DuplicateDecision(DuplicateKind kind, Path matchedPath, String matchedEntry, double confidence, String reason) {
    public DuplicateDecision {
        kind = kind == null ? DuplicateKind.NONE : kind;
        matchedEntry = matchedEntry == null ? "" : matchedEntry;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = reason == null ? "" : reason;
    }

    /** Compatibility constructor for ordinary matched files. */
    public DuplicateDecision(DuplicateKind kind, Path matchedPath, double confidence, String reason) {
        this(kind, matchedPath, "", confidence, reason);
    }

    public boolean matchedArchiveEntry() { return !matchedEntry.isBlank(); }

    public String matchedLabel() {
        if (matchedPath == null) return "";
        return matchedArchiveEntry() ? matchedPath + "!/" + matchedEntry : matchedPath.toString();
    }

    public static DuplicateDecision none() { return new DuplicateDecision(DuplicateKind.NONE, null, "", 0.0, ""); }
}
