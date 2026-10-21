package com.myhomelibcorp.infrastructure.collection.watch;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * Independent JVM fault-injection process for the incoming-folder SQLite journal.
 * Never opens or changes a user library: database path is supplied by the JUnit temp fixture.
 */
public final class IncomingFolderWatchCrashWorker {
    private IncomingFolderWatchCrashWorker() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("db, mode, signal, count required");
        Path database = Path.of(args[0]);
        String mode = args[1];
        Path signal = Path.of(args[2]);
        int expected = Integer.parseInt(args[3]);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            connection.setAutoCommit(false);
            int affected;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE incoming_folder_file
                       SET status='PROCESSING', updated_at='crash-fixture'
                     WHERE collection_id='c1' AND status=?
                    """)) {
                update.setString(1, "COMMITTED".equals(mode) ? "READY" : "WAITING");
                affected = update.executeUpdate();
            }
            if (affected != expected) {
                throw new IllegalStateException("Expected " + expected + " rows, found " + affected);
            }

            if ("UNCOMMITTED".equals(mode)) {
                signal(signal);
                // Parent forcibly kills this JVM with SQLite's write transaction still open.
                Thread.sleep(300_000);
                throw new AssertionError("Uncommitted worker was not killed by the test");
            }
            if ("COMMITTED".equals(mode)) {
                connection.commit();
                signal(signal);
                // Sudden JVM termination after a durable commit; normal close never runs.
                Runtime.getRuntime().halt(83);
            }
            throw new IllegalArgumentException("Unknown crash mode");
        }
    }

    private static void signal(Path signal) throws Exception {
        try (FileChannel file = FileChannel.open(signal, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer bytes = StandardCharsets.UTF_8.encode("transaction-boundary-reached");
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
    }
}
