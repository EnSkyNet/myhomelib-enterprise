package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import static com.myhomelibcorp.application.operation.LibraryOperationLeaseSupport.withSharedLease;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

@Component
public final class ReadingListQueryExecutor {
    private final LibraryOperationCoordinator operationCoordinator;
    public ReadingListQueryExecutor(LibraryOperationCoordinator operationCoordinator) {
        this.operationCoordinator = Objects.requireNonNull(operationCoordinator);
    }
    public <T> T execute(Supplier<T> work) {
        return withSharedLease(operationCoordinator, LibraryOperationType.QUERY, work);
    }
}
