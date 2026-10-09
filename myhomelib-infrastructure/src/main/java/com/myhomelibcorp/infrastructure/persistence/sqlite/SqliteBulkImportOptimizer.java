package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Applies connection-scoped SQLite import tuning only while a collection transaction is active.
 *
 * <p>SQLite PRAGMA values such as {@code cache_size} and {@code mmap_size} belong to a JDBC
 * connection. Executing them through {@link JdbcTemplate} outside a transaction is unsafe with a
 * pool because consecutive calls are not guaranteed to use the same connection. During an active
 * Spring JDBC transaction the connection is bound to the current thread, so both tuning and the
 * import use the same physical connection.</p>
 *
 * <p>{@code synchronous=NORMAL} and {@code temp_store=FILE} are intentionally not toggled here:
 * they are stable per-connection defaults configured by {@code DataSourceConfig}. In particular,
 * changing {@code synchronous} after a SQLite transaction has started is rejected by SQLite.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SqliteBulkImportOptimizer implements BulkImportOptimizer {

    private static final long BULK_CACHE_SIZE_KIB = -32768L; // 32 MiB, negative value means KiB.
    private static final long BULK_MMAP_SIZE_BYTES = 67108864L; // 64 MiB virtual mapping ceiling.

    private final CollectionManager collectionManager;

    /** Supports nested enable/disable calls on the same transaction-bound connection. */
    private final ThreadLocal<Deque<PragmaState>> previousStates = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public void enableBulkInsertMode() {
        DataSource dataSource = collectionManager.getCurrentDataSource();
        if (!hasTransactionBoundConnection(dataSource)) {
            log.debug("SQLite bulk PRAGMA tuning skipped: no transaction-bound collection connection");
            return;
        }

        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        PragmaState previous;
        try {
            previous = readState(jdbc);
        } catch (RuntimeException error) {
            log.warn("Не вдалося зчитати SQLite PRAGMA для transaction-bound bulk mode; оптимізацію пропущено: {}",
                    error.getMessage());
            return;
        }

        try {
            jdbc.execute("PRAGMA cache_size = " + BULK_CACHE_SIZE_KIB);
            jdbc.execute("PRAGMA mmap_size = " + BULK_MMAP_SIZE_BYTES);
            previousStates.get().push(previous);
            log.debug("SQLite bulk PRAGMA applied to the transaction-bound connection");
        } catch (RuntimeException error) {
            restoreState(jdbc, previous, "після помилки ввімкнення bulk mode");
            log.warn("Не вдалося встановити SQLite bulk PRAGMA; попередні значення відновлено: {}",
                    error.getMessage());
        }
    }

    @Override
    public void disableBulkInsertMode() {
        Deque<PragmaState> stack = previousStates.get();
        if (stack.isEmpty()) {
            previousStates.remove();
            return;
        }

        DataSource dataSource = collectionManager.getCurrentDataSource();
        if (!hasTransactionBoundConnection(dataSource)) {
            // Never restore through an arbitrary pooled connection. This should only be reachable
            // if a caller violates the enable/disable contract by ending the transaction first.
            stack.clear();
            previousStates.remove();
            log.warn("SQLite bulk PRAGMA state could not be restored safely because the transaction-bound connection is gone");
            return;
        }

        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        PragmaState previous = stack.pop();
        try {
            restoreState(jdbc, previous, "після bulk import");
            log.debug("SQLite bulk PRAGMA restored on the transaction-bound connection");
        } finally {
            if (stack.isEmpty()) previousStates.remove();
        }
    }

    private static boolean hasTransactionBoundConnection(DataSource dataSource) {
        return dataSource != null
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(dataSource);
    }

    private static PragmaState readState(JdbcTemplate jdbc) {
        return new PragmaState(
                requireLong(jdbc, "PRAGMA cache_size"),
                requireLong(jdbc, "PRAGMA mmap_size"));
    }

    private static long requireLong(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        if (value == null) throw new IllegalStateException("SQLite returned NULL for " + sql);
        return value;
    }

    private static void restoreState(JdbcTemplate jdbc, PragmaState state, String context) {
        RuntimeException failure = null;
        failure = restoreOne(jdbc, "PRAGMA cache_size = " + state.cacheSize(), failure);
        failure = restoreOne(jdbc, "PRAGMA mmap_size = " + state.mmapSize(), failure);
        if (failure != null) {
            log.warn("Не всі SQLite PRAGMA вдалося відновити {}", context, failure);
        }
    }

    private static RuntimeException restoreOne(JdbcTemplate jdbc, String sql, RuntimeException previousFailure) {
        try {
            jdbc.execute(sql);
            return previousFailure;
        } catch (RuntimeException error) {
            if (previousFailure == null) return error;
            previousFailure.addSuppressed(error);
            return previousFailure;
        }
    }

    private record PragmaState(long cacheSize, long mmapSize) { }
}
