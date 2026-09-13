package com.myhomelibcorp.infrastructure.persistence;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** Executes short units of infrastructure work against the currently selected collection transactionally. */
public final class CollectionTransactionExecutor {
    private CollectionTransactionExecutor() {
    }

    public static void run(CollectionManager collectionManager, Runnable work) {
        Objects.requireNonNull(work, "work");
        execute(collectionManager, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T execute(CollectionManager collectionManager, Supplier<T> work) {
        Objects.requireNonNull(collectionManager, "collectionManager");
        Objects.requireNonNull(work, "work");
        var dataSource = collectionManager.getCurrentDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("Current collection is not selected");
        }
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return transaction.execute(status -> work.get());
    }
}
