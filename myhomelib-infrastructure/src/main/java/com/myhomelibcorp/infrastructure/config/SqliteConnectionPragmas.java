package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import org.sqlite.SQLiteConfig;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Applies SQLite connection-local PRAGMAs through JDBC driver properties.
 *
 * <p>Do not concatenate multiple PRAGMA statements into Hikari's connectionInitSql:
 * Xerial SQLite JDBC executes that hook as one statement and later semicolon-separated
 * PRAGMAs are not guaranteed to be applied. Driver properties are evaluated for every
 * newly opened physical connection.</p>
 */
final class SqliteConnectionPragmas {
    private SqliteConnectionPragmas() {
    }

    static void applyBase(HikariConfig hikari, int busyTimeoutMs) {
        Objects.requireNonNull(hikari, "hikari");
        if (busyTimeoutMs < 0) throw new IllegalArgumentException("busyTimeoutMs must be >= 0");

        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(busyTimeoutMs);
        apply(hikari, sqlite.toProperties());
    }

    static void applyInteractive(HikariConfig hikari,
                                 int busyTimeoutMs,
                                 int cacheSizeKiB,
                                 long mmapSizeBytes) {
        if (cacheSizeKiB >= 0) throw new IllegalArgumentException("cacheSizeKiB must be negative (KiB semantics)");
        if (mmapSizeBytes < 0) throw new IllegalArgumentException("mmapSizeBytes must be >= 0");

        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(busyTimeoutMs);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.setTempStore(SQLiteConfig.TempStore.FILE); // large sort/index buffers may spill to disk
        sqlite.setCacheSize(cacheSizeKiB);
        sqlite.setPragma(SQLiteConfig.Pragma.MMAP_SIZE, Long.toString(mmapSizeBytes));
        apply(hikari, sqlite.toProperties());
    }

    private static void apply(HikariConfig hikari, Properties properties) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            hikari.addDataSourceProperty(String.valueOf(entry.getKey()), entry.getValue());
        }
    }
}
