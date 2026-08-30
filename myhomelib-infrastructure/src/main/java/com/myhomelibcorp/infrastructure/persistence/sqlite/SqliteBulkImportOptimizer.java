package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqliteBulkImportOptimizer implements BulkImportOptimizer {

    private final CollectionManager collectionManager;

    /** Supports properly nested enable/disable calls without losing the caller's original PRAGMA values. */
    private final ThreadLocal<Deque<PragmaState>> previousStates = ThreadLocal.withInitial(ArrayDeque::new);

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public void enableBulkInsertMode() {
        JdbcTemplate jt = getJdbcTemplate();
        PragmaState previous;
        try {
            previous = readState(jt);
        } catch (RuntimeException e) {
            log.warn("Не вдалося зчитати поточні SQLite PRAGMA; bulk-оптимізацію пропущено: {}", e.getMessage());
            return;
        }

        try {
            jt.execute("PRAGMA synchronous = NORMAL");
            jt.execute("PRAGMA temp_store = MEMORY");
            jt.execute("PRAGMA cache_size = -262144");
            jt.execute("PRAGMA mmap_size = 2147483648");
            previousStates.get().push(previous);
            log.debug("PRAGMA встановлено для швидкого імпорту; попередній стан збережено");
        } catch (RuntimeException e) {
            restoreState(jt, previous, "після помилки ввімкнення bulk mode");
            log.warn("Помилка при встановленні PRAGMA для імпорту; попередні значення відновлено: {}", e.getMessage());
        }
    }

    @Override
    public void disableBulkInsertMode() {
        Deque<PragmaState> stack = previousStates.get();
        if (stack.isEmpty()) {
            previousStates.remove();
            log.debug("Немає збереженого SQLite PRAGMA state для відновлення");
            return;
        }

        JdbcTemplate jt = getJdbcTemplate();
        PragmaState previous = stack.pop();
        try {
            restoreState(jt, previous, "після bulk import");
            log.debug("SQLite PRAGMA відновлено до фактичних попередніх значень");
        } finally {
            if (stack.isEmpty()) previousStates.remove();
        }
    }

    private static PragmaState readState(JdbcTemplate jt) {
        return new PragmaState(
                requireLong(jt, "PRAGMA synchronous"),
                requireLong(jt, "PRAGMA temp_store"),
                requireLong(jt, "PRAGMA cache_size"),
                requireLong(jt, "PRAGMA mmap_size"));
    }

    private static long requireLong(JdbcTemplate jt, String sql) {
        Long value = jt.queryForObject(sql, Long.class);
        if (value == null) throw new IllegalStateException("SQLite returned NULL for " + sql);
        return value;
    }

    private static void restoreState(JdbcTemplate jt, PragmaState state, String context) {
        RuntimeException failure = null;
        failure = restoreOne(jt, "PRAGMA synchronous = " + state.synchronous(), failure);
        failure = restoreOne(jt, "PRAGMA temp_store = " + state.tempStore(), failure);
        failure = restoreOne(jt, "PRAGMA cache_size = " + state.cacheSize(), failure);
        failure = restoreOne(jt, "PRAGMA mmap_size = " + state.mmapSize(), failure);
        if (failure != null) {
            log.warn("Не всі SQLite PRAGMA вдалося відновити {}", context, failure);
        }
    }

    private static RuntimeException restoreOne(JdbcTemplate jt, String sql, RuntimeException previousFailure) {
        try {
            jt.execute(sql);
            return previousFailure;
        } catch (RuntimeException e) {
            if (previousFailure == null) return e;
            previousFailure.addSuppressed(e);
            return previousFailure;
        }
    }

    private record PragmaState(long synchronous, long tempStore, long cacheSize, long mmapSize) { }
}
