package com.myhomelibcorp.ui.organizer;

import com.myhomelibcorp.organizer.analysis.AuthorFolderOrderPreview;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A deliberately non-executable handoff to the offline author-folder repair tool.
 * The file contains only exact source folder names, one per line. It is not an
 * authorization to rename: the offline tool independently verifies the SQLite
 * catalog, files, checksums and approval code before modifying anything.
 */
final class AuthorFolderRepairSelection {
    static final int MAX_FOLDERS = 10;

    private AuthorFolderRepairSelection() { }

    static String render(List<AuthorFolderOrderPreview.Result> selected) {
        if (selected == null || selected.isEmpty() || selected.size() > MAX_FOLDERS) {
            throw new IllegalArgumentException("Select between one and ten folders");
        }
        StringBuilder out = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (var candidate : selected) {
            if (candidate == null || candidate.status() != AuthorFolderOrderPreview.Status.PROPOSAL
                    || candidate.matchingBooks() < 1
                    || !safeComponent(candidate.currentName()) || !safeComponent(candidate.proposedName())
                    || candidate.currentName().equalsIgnoreCase(candidate.proposedName())) {
                throw new IllegalArgumentException("Unsafe or unverified author folder proposal");
            }
            if (!seen.add(candidate.currentName().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate author folder proposal");
            }
            out.append(candidate.currentName()).append('\n');
        }
        return out.toString();
    }

    private static boolean safeComponent(String value) {
        if (value == null || value.isBlank() || value.startsWith("#") || value.equals(".") || value.equals("..")
                || !value.equals(value.strip()) || value.endsWith(".")) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) return false;
        }
        return true;
    }
}
