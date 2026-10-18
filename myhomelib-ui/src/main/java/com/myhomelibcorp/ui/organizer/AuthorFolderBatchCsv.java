package com.myhomelibcorp.ui.organizer;

import com.myhomelibcorp.organizer.analysis.AuthorFolderOrderPreview;
import java.util.List;
import java.util.Map;

/** Spreadsheet-safe localized export, not a rename plan or a source of file mutations. */
final class AuthorFolderBatchCsv {
    private AuthorFolderBatchCsv() { }

    static String render(List<AuthorFolderOrderPreview.Result> rows,
                         List<String> columns, Map<AuthorFolderOrderPreview.Status, String> statuses) {
        if (columns.size() != 4) throw new IllegalArgumentException("Expected four CSV headers");
        StringBuilder out = new StringBuilder(Math.max(128, rows.size() * 128));
        out.append('\uFEFF'); // Excel UTF-8 BOM
        appendRow(out, columns);
        for (var row : rows) {
            appendRow(out, List.of(row.currentName(), row.proposedName(),
                    Long.toString(row.matchingBooks()), statuses.get(row.status())));
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) out.append(';');
            String text = columns.get(i) == null ? "" : columns.get(i);
            // Excel interprets even quoted cells beginning with these characters as formulas.
            String stripped = text.stripLeading();
            if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) text = "'" + text;
            out.append('"').append(text.replace("\"", "\"\"")).append('"');
        }
        out.append('\n');
    }
}
