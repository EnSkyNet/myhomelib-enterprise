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
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int total;

        private BoundedByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (subscription == null) {
                body.completeExceptionally(new NullPointerException("subscription"));
                return;
            }
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
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
                    byte[] chunk = new byte[count];
                    buffer.get(chunk);
                    output.writeBytes(chunk);
                    total += count;
                }
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
