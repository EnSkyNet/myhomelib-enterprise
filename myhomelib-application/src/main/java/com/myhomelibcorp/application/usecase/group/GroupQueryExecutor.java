package com.myhomelibcorp.application.usecase.group;

import static com.myhomelibcorp.application.operation.LibraryOperationLeaseSupport.withSharedLease;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** Keeps group reads on one active collection for their complete SQL query/mapping lifetime. */
@Component
public class GroupQueryExecutor {
    private final LibraryOperationCoordinator operationCoordinator;

    public GroupQueryExecutor(LibraryOperationCoordinator operationCoordinator) {
        this.operationCoordinator = Objects.requireNonNull(operationCoordinator, "operationCoordinator");
    }

    public <T> T execute(Supplier<T> query) {
        Objects.requireNonNull(query, "query");
        return withSharedLease(operationCoordinator, LibraryOperationType.QUERY, query);
    }
}
