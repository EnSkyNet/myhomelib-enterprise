package com.myhomelibcorp.shared.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** HTTP body handlers that abort the subscription before an untrusted response can grow without bound. */
public final class BoundedHttpBodyHandlers {
    private BoundedHttpBodyHandlers() { }

    public static HttpResponse.BodyHandler<byte[]> ofByteArray(int maxBytes) {
        validateLimit(maxBytes);
        return ignored -> new BoundedByteArraySubscriber(maxBytes);
    }

    public static HttpResponse.BodyHandler<String> ofString(int maxBytes, Charset charset) {
        if (charset == null) throw new IllegalArgumentException("charset cannot be null");
        validateLimit(maxBytes);
        return ignored -> HttpResponse.BodySubscribers.mapping(
                new BoundedByteArraySubscriber(maxBytes),
                bytes -> new String(bytes, charset));
    }

    private static void validateLimit(int maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
    }

    private static final class BoundedByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private static final int COPY_BUFFER_BYTES = 8 * 1024;
        private final ByteArrayOutputStream output;
        private byte[] scratch;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;
        private int total;

        private BoundedByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 1024));
            body.whenComplete((value, error) -> {
                if (body.isCancelled()) {
                    Flow.Subscription current = subscription;
                    if (current != null) current.cancel();
                }
            });
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription next) {
            if (next == null) {
                body.completeExceptionally(new NullPointerException("subscription"));
                return;
            }
            if (subscription != null || body.isDone()) {
                next.cancel();
                return;
            }
            subscription = next;
            if (body.isDone()) {
                next.cancel();
            } else {
                next.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            try {
                for (ByteBuffer buffer : buffers) {
                    int count = buffer.remaining();
                    if (count > maxBytes - total) {
                        if (subscription != null) subscription.cancel();
                        body.completeExceptionally(new IOException(
                                "HTTP response body exceeds maximum allowed size: " + maxBytes + " bytes"));
                        return;
                    }
                    if (buffer.hasArray()) {
                        output.write(buffer.array(), buffer.arrayOffset() + buffer.position(), count);
                        buffer.position(buffer.limit());
                    } else {
                        while (buffer.hasRemaining()) {
                            if (scratch == null) scratch = new byte[Math.min(COPY_BUFFER_BYTES, buffer.remaining())];
                            int chunk = Math.min(buffer.remaining(), scratch.length);
                            buffer.get(scratch, 0, chunk);
                            output.write(scratch, 0, chunk);
                        }
                    }
                    total += count;
                }
                if (!body.isDone() && subscription != null) subscription.request(1);
            } catch (RuntimeException e) {
                if (subscription != null) subscription.cancel();
                body.completeExceptionally(e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!body.isDone()) body.complete(output.toByteArray());
        }
    }
}
