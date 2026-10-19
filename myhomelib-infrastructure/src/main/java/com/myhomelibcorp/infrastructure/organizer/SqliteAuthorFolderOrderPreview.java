package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.organizer.analysis.AuthorFolderOrderPreview;
import com.myhomelibcorp.organizer.analysis.AuthorFolderPreviewPort;

import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only preview for a single author folder. Unlike the offline repair command,
 * this deliberately does not inspect every file, compute hashes or change SQLite.
 * No result from this class is permission to rename a folder.
 */
@org.springframework.stereotype.Component
public final class SqliteAuthorFolderOrderPreview implements AuthorFolderPreviewPort {

    public AuthorFolderOrderPreview.Result inspect(Path database, Path root, String folderName) throws SQLException {
        if (database == null || !Files.isRegularFile(database) || Files.isSymbolicLink(database)) {
            throw new IllegalArgumentException("Catalog file is not accessible");
        }
        if (root == null || !Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Collection root is not accessible");
        }
        if (!validComponent(folderName) || !Files.isDirectory(root.resolve(folderName))
                || Files.isSymbolicLink(root.resolve(folderName))) {
            throw new IllegalArgumentException("Author folder is not accessible");
        }
        String rootPath = root.toAbsolutePath().normalize().toString();
        String descendantPattern = escapeLike(folderName) + "/%";
        long books;
        Set<String> names = new LinkedHashSet<>();
        long booksWithAuthors;
        // URI read-only mode and PRAGMA query_only protect against accidental writes.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro")) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=2500");
                statement.execute("PRAGMA query_only=ON");
                statement.execute("BEGIN");
            }
            String folderFilter = "(b.folder = ? OR b.folder LIKE ? ESCAPE '!') AND b.local <> 0 AND b.deleted = 0 AND b.collection_root = ?";
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM books b WHERE " + folderFilter)) {
                query.setString(1, folderName);
                query.setString(2, descendantPattern);
                query.setString(3, rootPath);
                try (ResultSet result = query.executeQuery()) { books = result.next() ? result.getLong(1) : 0; }
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT DISTINCT b.id,a.first_name,a.middle_name,a.last_name FROM books b "
                            + "JOIN book_authors ba ON ba.book_id=b.id JOIN authors a ON a.id=ba.author_id WHERE "
                            + folderFilter)) {
                query.setString(1, folderName);
                query.setString(2, descendantPattern);
                query.setString(3, rootPath);
                Set<String> matchedIds = new LinkedHashSet<>();
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        matchedIds.add(result.getString(1));
                        String first = normalized(result.getString(2));
                        String middle = normalized(result.getString(3));
                        String last = normalized(result.getString(4));
                        if (!first.isEmpty() && !last.isEmpty()) {
                            names.add(normalized(last + " " + first + (middle.isEmpty() ? "" : " " + middle)));
                        } else {
                            names.add(""); // incomplete identity is never safe to infer
                        }
                        if (names.size() > 1) break;
                    }
                }
                booksWithAuthors = matchedIds.size();
            }
        }
        if (books == 0) return new AuthorFolderOrderPreview.Result(folderName, "", 0, AuthorFolderOrderPreview.Status.NO_BOOKS);
        if (names.size() != 1 || names.contains("") || booksWithAuthors != books) {
            return new AuthorFolderOrderPreview.Result(folderName, "", books, AuthorFolderOrderPreview.Status.AMBIGUOUS);
        }
        String proposed = names.iterator().next();
        if (!validComponent(proposed)) return new AuthorFolderOrderPreview.Result(folderName, "", books, AuthorFolderOrderPreview.Status.AMBIGUOUS);
        if (proposed.equals(folderName)) return new AuthorFolderOrderPreview.Result(folderName, proposed, books, AuthorFolderOrderPreview.Status.ALREADY_CORRECT);
        // Case-only renames require an explicit two-step operation on Windows.
        if (normalized(proposed).equalsIgnoreCase(normalized(folderName))) {
            return new AuthorFolderOrderPreview.Result(folderName, proposed, books, AuthorFolderOrderPreview.Status.TARGET_EXISTS);
        }
        // Windows collates names case-insensitively. Even on Linux warn on such collisions.
        try (var entries = Files.list(root)) {
            if (entries.anyMatch(path -> !path.getFileName().toString().equals(folderName)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT)
                    .equals(proposed.toLowerCase(Locale.ROOT)))) {
                return new AuthorFolderOrderPreview.Result(folderName, proposed, books, AuthorFolderOrderPreview.Status.TARGET_EXISTS);
            }
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Cannot inspect folder names", ex);
        }
        return new AuthorFolderOrderPreview.Result(folderName, proposed, books, AuthorFolderOrderPreview.Status.PROPOSAL);
    }

    /**
     * Reads local catalog metadata in one consistent SQLite snapshot and enumerates the
     * immediate author directories once. Memory scales with folder count, not book count.
     * This is a diagnostic report only; no row authorizes file mutation.
     */
    @Override
    public List<AuthorFolderOrderPreview.Result> inspectAll(Path database, Path root) throws SQLException, IOException {
        if (database == null || !Files.isRegularFile(database) || Files.isSymbolicLink(database)) {
            throw new IllegalArgumentException("Catalog file is not accessible");
        }
        if (root == null || !Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Collection root is not accessible");
        }
        // A bounded directory listing protects UI report generation against hostile trees.
        Map<String, Boolean> physicalFolders = new LinkedHashMap<>();
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
            for (Path entry : directory) {
                if (!Files.isSymbolicLink(entry) && Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    String name = entry.getFileName().toString();
                    if (validComponent(name)) {
                        if (physicalFolders.size() >= 100_000) {
                            throw new IOException("Too many author directories for a single report");
                        }
                        physicalFolders.put(name, Boolean.TRUE);
                    }
                }
            }
        }
        Map<String, FolderSummary> metadata = new LinkedHashMap<>();
        String rootPath = root.toAbsolutePath().normalize().toString();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro")) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=2500");
                statement.execute("PRAGMA query_only=ON");
                statement.execute("BEGIN");
            }
            // LEFT JOIN keeps books with no authors: they must be reported ambiguous.
            // Book rows are grouped by folder/id so multi-author books count once.
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT b.id,b.folder,a.first_name,a.middle_name,a.last_name "
                    + "FROM books b LEFT JOIN book_authors ba ON ba.book_id=b.id "
                    + "LEFT JOIN authors a ON a.id=ba.author_id "
                    + "WHERE b.collection_root=? AND b.local <> 0 AND b.deleted=0 "
                    + "AND b.folder IS NOT NULL ORDER BY b.folder,b.id")) {
                query.setString(1, rootPath);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        String folder = rows.getString(2);
                        if (folder.isEmpty() || folder.charAt(0) == '/') continue;
                        int slash = folder.indexOf('/');
                        String top = slash < 0 ? folder : folder.substring(0, slash);
                        if (!physicalFolders.containsKey(top)) continue;
                        FolderSummary summary = metadata.computeIfAbsent(top, unused -> new FolderSummary());
                        String bookId = rows.getString(1);
                        if (!bookId.equals(summary.lastBookId)) {
                            summary.bookCount++;
                            summary.lastBookId = bookId;
                        }
                        String first = normalized(rows.getString(3));
                        String middle = normalized(rows.getString(4));
                        String last = normalized(rows.getString(5));
                        String proposed = first.isEmpty() || last.isEmpty()
                                ? "" : normalized(last + " " + first + (middle.isEmpty() ? "" : " " + middle));
                        if (proposed.isEmpty() || !validComponent(proposed)) {
                            summary.ambiguous = true;
                        } else if (summary.target == null) {
                            summary.target = proposed;
                        } else if (!summary.target.equals(proposed)) {
                            summary.ambiguous = true;
                        }
                    }
                }
            }
        }
        Set<String> normalizedFolders = new LinkedHashSet<>();
        for (String name : physicalFolders.keySet()) {
            normalizedFolders.add(normalized(name).toLowerCase(Locale.ROOT));
        }
        List<AuthorFolderOrderPreview.Result> results = new ArrayList<>(physicalFolders.size());
        for (String name : physicalFolders.keySet()) {
            FolderSummary summary = metadata.get(name);
            if (summary == null || summary.bookCount == 0) {
                results.add(new AuthorFolderOrderPreview.Result(name, "", 0, AuthorFolderOrderPreview.Status.NO_BOOKS));
                continue;
            }
            if (summary.ambiguous || summary.target == null) {
                results.add(new AuthorFolderOrderPreview.Result(name, "", summary.bookCount,
                        AuthorFolderOrderPreview.Status.AMBIGUOUS));
                continue;
            }
            String target = summary.target;
            AuthorFolderOrderPreview.Status status;
            if (name.equals(target)) {
                status = AuthorFolderOrderPreview.Status.ALREADY_CORRECT;
            } else if (normalizedFolders.contains(normalized(target).toLowerCase(Locale.ROOT))) {
                // Case-only name changes are also unsafe on case-insensitive Windows volumes.
                status = AuthorFolderOrderPreview.Status.TARGET_EXISTS;
            } else {
                status = AuthorFolderOrderPreview.Status.PROPOSAL;
            }
            results.add(new AuthorFolderOrderPreview.Result(name, target, summary.bookCount, status));
        }
        results.sort(Comparator.comparing(AuthorFolderOrderPreview.Result::currentName,
                String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(results);
    }

    private static final class FolderSummary {
        private String lastBookId;
        private String target;
        private long bookCount;
        private boolean ambiguous;
    }

    private static String normalized(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
    }

    private static boolean validComponent(String component) {
        return component != null && !component.isBlank() && !component.equals(".") && !component.equals("..")
                && !component.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*") && !component.endsWith(" ")
                && !component.endsWith(".");
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
