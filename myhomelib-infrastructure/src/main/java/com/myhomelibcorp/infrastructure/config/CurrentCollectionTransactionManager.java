package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Binds each transaction to the physical DataSource used by collection repositories.
 *
 * CollectionManager exposes a JdbcTemplate built on its current physical pool, not on
 * the routing DataSource bean. A transaction manager using the routing bean as its key
 * would bind a *different* connection and repository writes could autocommit outside
 * the apparent transaction. Keep the physical manager which began a transaction until
 * its matching commit/rollback, even if the active collection changes meanwhile.
 */
public final class CurrentCollectionTransactionManager implements PlatformTransactionManager {
    private final CollectionManager collections;
    private final Map<TransactionStatus, PlatformTransactionManager> owners =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public CurrentCollectionTransactionManager(CollectionManager collections) {
        this.collections = Objects.requireNonNull(collections, "collections");
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        DataSource active = collections.getCurrentDataSource();
        if (active == null) throw new IllegalTransactionStateException("No active library collection");
        PlatformTransactionManager manager = new DataSourceTransactionManager(active);
        TransactionStatus status = manager.getTransaction(definition);
        owners.put(status, manager);
        return status;
    }

    @Override
    public void commit(TransactionStatus status) {
        ownerFor(status).commit(status);
    }

    @Override
    public void rollback(TransactionStatus status) {
        ownerFor(status).rollback(status);
    }

    private PlatformTransactionManager ownerFor(TransactionStatus status) {
        PlatformTransactionManager owner = owners.remove(status);
        if (owner == null) throw new IllegalTransactionStateException("Unknown or completed collection transaction");
        return owner;
    }
}
