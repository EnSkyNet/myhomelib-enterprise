package com.myhomelibcorp.infrastructure.organizer;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/** Isolated hard-termination worker. Intentionally halts before SQLite COMMIT. */
public final class SqliteOrganizerCrashWorker {
    private SqliteOrganizerCrashWorker() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("db, operation, item, target expected");
        Path db = Path.of(args[0]);
        Path target = Path.of(args[3]);
        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (Statement statement = sqlite.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
            }
            sqlite.setAutoCommit(false);
            try (PreparedStatement item = sqlite.prepareStatement("UPDATE library_organization_items "
                    + "SET state='COMMITTED' WHERE operation_id=? AND item_id=?")) {
                item.setString(1, args[1]);
                item.setString(2, args[2]);
                if (item.executeUpdate() != 1) throw new IllegalStateException("Journal row missing");
            }
            // Publication already happened while the SQLite transaction was not committed.
            try (FileChannel file = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer data = StandardCharsets.UTF_8.encode("crash-test-book-bytes");
                while (data.hasRemaining()) file.write(data);
                file.force(true);
            }
            Runtime.getRuntime().halt(83);
        }
    }
}
