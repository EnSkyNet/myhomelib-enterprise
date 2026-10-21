package com.myhomelibcorp.ui.metadata;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class BulkMetadataEditCauseCycleTest {
    @Test
    void detectsCancellationWrappedInCompletionException() {
        Throwable error = new CompletionException("wrapper", new CancellationException("cancelled"));
        assertTrue(BulkMetadataEditUiService.isCancellation(error));
    }

    @Test
    void doesNotMistakeOrdinaryFailureForCancellation() {
        Throwable error = new CompletionException(new IllegalStateException("failed"));
        assertFalse(BulkMetadataEditUiService.isCancellation(error));
    }

    @Test
    void cyclicChainWithoutCancellationReturnsFalseWithoutHanging() {
        Exception first = new Exception("first");
        Exception second = new Exception("second");
        first.initCause(second);
        second.initCause(first);
        assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertFalse(BulkMetadataEditUiService.isCancellation(first)));
    }

    @Test
    void threeNodeCycleContainingCancellationIsRecognized() {
        Exception first = new Exception("outer");
        CancellationException cancelled = new CancellationException("cancelled");
        Exception third = new Exception("inner");
        first.initCause(cancelled);
        cancelled.initCause(third);
        third.initCause(first);
        assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertTrue(BulkMetadataEditUiService.isCancellation(first)));
    }

    @Test
    void nullErrorIsNotCancellation() {
        assertFalse(BulkMetadataEditUiService.isCancellation(null));
    }

    @Test
    void cancellationInsideNestedExecutionExceptionIsRecognized() {
        Throwable error = new CompletionException(new ExecutionException(
                new CancellationException("user cancelled")));
        assertTrue(BulkMetadataEditUiService.isCancellation(error));
    }
}
