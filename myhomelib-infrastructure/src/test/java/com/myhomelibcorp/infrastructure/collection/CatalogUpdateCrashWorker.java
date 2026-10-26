package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.shared.util.CatalogUpdateRecoveryFiles;

import java.nio.file.Path;
import java.sql.DriverManager;

/**
 * External JVM child for real SQLite WAL process-abort regression tests.
 * Its expected exit is Runtime.halt(83), without closing JDBC connections.
 */
public final class CatalogUpdateCrashWorker {
    private CatalogUpdateCrashWorker() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("db, collectionId and crashPoint required");
        Path database = Path.of(args[0]);
        String collectionId = args[1];
        String crashPoint = args[2];

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            try (var mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) {
                    throw new IllegalStateException("WAL mode unavailable");
                }
            }
            statement.execute("PRAGMA synchronous=FULL");

            if ("BEFORE_MARKER".equals(crashPoint)) halt();
            CatalogUpdateRecoveryFiles.markPending(collectionId, "crash-test-" + crashPoint);
            if ("AFTER_MARKER".equals(crashPoint)) halt();

            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE recovery_state SET value='partially-updated'");
            if ("DURING_TRANSACTION".equals(crashPoint)) halt();
            connection.commit();
            if ("AFTER_SQLITE_COMMIT".equals(crashPoint)) halt();

            if ("AFTER_MARKER_REMOVAL".equals(crashPoint)) {
                CatalogUpdateRecoveryFiles.deleteMarkerOnly(collectionId);
                halt();
            }
        }
        throw new IllegalStateException("Unrecognized crash point: " + crashPoint);
    }

    private static void halt() {
        System.out.println("Crash test worker: Аварійне завершення");
        System.err.println("Crash test worker stderr: Аварійне завершення");
        Runtime.getRuntime().halt(83);
    }
}
