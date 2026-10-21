package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpBodyHandlersTest {
    private static final class ProbeSubscription implements Flow.Subscription {
        long requests;
        int cancellations;

        @Override public void request(long amount) { requests += amount; }
        @Override public void cancel() { cancellations++; }
    }

    private static HttpResponse.BodySubscriber<byte[]> subscriber(int limit) {
        return BoundedHttpBodyHandlers.ofByteArray(limit).apply(null);
    }

    @Test
    void acceptsExactlyMaximumBytesAcrossSignals() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(3);
        ProbeSubscription flow = new ProbeSubscription();
        body.onSubscribe(flow);
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {3})));
        body.onComplete();
        assertArrayEquals(new byte[] {1, 2, 3}, body.getBody().toCompletableFuture().join());
        assertEquals(3, flow.requests);
        assertEquals(0, flow.cancellations);
    }

    @Test
    void rejectsOverflowWithinOneSignalWithoutConsumingOffendingBuffer() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(2);
        ProbeSubscription flow = new ProbeSubscription();
        body.onSubscribe(flow);
        ByteBuffer over = ByteBuffer.wrap(new byte[] {1, 2, 3});
        body.onNext(List.of(over));
        assertEquals(0, over.position());
        assertEquals(1, flow.cancellations);
        CompletionException e = assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void rejectsOverflowAcrossSignalsAndCancelsTransport() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(2);
        ProbeSubscription flow = new ProbeSubscription();
        body.onSubscribe(flow);
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {3})));
        body.onComplete();
        assertEquals(1, flow.cancellations);
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    }

    @Test
    void consumesSlicedHeapBufferWithoutIncludingOutsideBytes() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(2);
        body.onSubscribe(new ProbeSubscription());
        ByteBuffer heap = ByteBuffer.wrap(new byte[] {9, 1, 2, 8});
        heap.position(1);
        heap.limit(3);
        body.onNext(List.of(heap));
        body.onComplete();
        assertArrayEquals(new byte[] {1, 2}, body.getBody().toCompletableFuture().join());
        assertEquals(3, heap.position());
    }

    @Test
    void consumesReadOnlyBuffer() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(3);
        body.onSubscribe(new ProbeSubscription());
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3}).asReadOnlyBuffer()));
        body.onComplete();
        assertArrayEquals(new byte[] {1, 2, 3}, body.getBody().toCompletableFuture().join());
    }

    @Test
    void copiesDirectBufferInBoundedChunks() {
        byte[] bytes = new byte[100_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
        direct.put(bytes).flip();
        HttpResponse.BodySubscriber<byte[]> body = subscriber(bytes.length);
        body.onSubscribe(new ProbeSubscription());
        body.onNext(List.of(direct));
        body.onComplete();
        assertArrayEquals(bytes, body.getBody().toCompletableFuture().join());
        assertEquals(direct.limit(), direct.position());
    }

    @Test
    void maintainsOneSignalOfDemandAtATime() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(10);
        ProbeSubscription flow = new ProbeSubscription();
        body.onSubscribe(flow);
        assertEquals(1, flow.requests);
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1})));
        assertEquals(2, flow.requests);
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {2})));
        assertEquals(3, flow.requests);
    }

    @Test
    void cancellingBodyCancelsSubscription() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(10);
        ProbeSubscription flow = new ProbeSubscription();
        body.onSubscribe(flow);
        assertTrue(body.getBody().toCompletableFuture().cancel(true));
        assertEquals(1, flow.cancellations);
    }

    @Test
    void cancellationBeforeSubscribeCancelsLateSubscription() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(10);
        assertTrue(body.getBody().toCompletableFuture().cancel(true));
        ProbeSubscription flow = new ProbeSubscription();
        body.onSubscribe(flow);
        assertEquals(1, flow.cancellations);
        assertEquals(0, flow.requests);
    }

    @Test
    void duplicateSubscriptionIsCancelled() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(10);
        ProbeSubscription first = new ProbeSubscription();
        ProbeSubscription duplicate = new ProbeSubscription();
        body.onSubscribe(first);
        body.onSubscribe(duplicate);
        assertEquals(1, first.requests);
        assertEquals(1, duplicate.cancellations);
    }

    @Test
    void transportFailureCompletesExceptionally() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(10);
        body.onSubscribe(new ProbeSubscription());
        body.onError(new IOException("transport failure"));
        CompletionException e = assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
        assertEquals("transport failure", e.getCause().getMessage());
    }

    @Test
    void emptyResponseCompletesSuccessfully() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(1);
        body.onSubscribe(new ProbeSubscription());
        body.onComplete();
        assertArrayEquals(new byte[0], body.getBody().toCompletableFuture().join());
    }

    @Test
    void mapsUtf8ResponseCorrectly() {
        HttpResponse.BodySubscriber<String> body = BoundedHttpBodyHandlers.ofString(16, StandardCharsets.UTF_8).apply(null);
        body.onSubscribe(new ProbeSubscription());
        body.onNext(List.of(ByteBuffer.wrap("Київ".getBytes(StandardCharsets.UTF_8))));
        body.onComplete();
        assertEquals("Київ", body.getBody().toCompletableFuture().join());
    }

    @Test
    void rejectsInvalidLimitsAndNullCharset() {
        assertThrows(IllegalArgumentException.class, () -> BoundedHttpBodyHandlers.ofByteArray(0));
        assertThrows(IllegalArgumentException.class, () -> BoundedHttpBodyHandlers.ofByteArray(-1));
        assertThrows(IllegalArgumentException.class, () -> BoundedHttpBodyHandlers.ofString(10, null));
    }

    @Test
    void malformedBufferSignalCancelsSubscription() {
        HttpResponse.BodySubscriber<byte[]> body = subscriber(3);
        ProbeSubscription flow = new ProbeSubscription();
        body.onSubscribe(flow);
        body.onNext(null);
        assertEquals(1, flow.cancellations);
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    }
}
