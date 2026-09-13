package com.myhomelibcorp.infrastructure.metadata;

import com.myhomelibcorp.application.metadata.MetadataProviderErrorKind;
import com.myhomelibcorp.application.metadata.MetadataProviderException;
import com.myhomelibcorp.application.metadata.MetadataRequestContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MetadataProviderSupportTest {
    @Test
    void rateLimitSlotsHandleNegativeNanoTimeAndSignedWraparound() {
        AtomicLong next = new AtomicLong(-1000);
        assertThat(MetadataProviderSupport.reserveRequestSlot(next, 100, -1000)).isEqualTo(-1000);
        assertThat(MetadataProviderSupport.reserveRequestSlot(next, 100, -990)).isEqualTo(-900);
        assertThat(MetadataProviderSupport.reserveRequestSlot(next, 100, -600)).isEqualTo(-600);
        next.set(Long.MAX_VALUE - 9);
        assertThat(MetadataProviderSupport.reserveRequestSlot(next, 20, Long.MAX_VALUE - 9))
                .isEqualTo(Long.MAX_VALUE - 9);
        long wrapped = next.get();
        assertThat(MetadataProviderSupport.reserveRequestSlot(next, 20, Long.MAX_VALUE - 4)).isEqualTo(wrapped);
        assertThat(next.get() - wrapped).isEqualTo(20);
    }

    @Test
    void cooperativeCancellationCancelsTheOutstandingHttpExchange() throws Exception {
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<String>> network = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        when(client.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(call -> { started.countDown(); return network; });
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> MetadataProviderSupport.send(client, request(),
                    MetadataRequestContext.create(Duration.ofSeconds(10), cancelled), "Provider"));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertThat(failure.getCause()).isInstanceOf(MetadataProviderException.class);
            assertThat(((MetadataProviderException) failure.getCause()).kind()).isEqualTo(MetadataProviderErrorKind.CANCELLED);
            assertThat(network.isCancelled()).isTrue();
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void deadlineCancelsPendingNetworkWorkAndPreCancellationDoesNotStartIt() {
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<String>> network = new CompletableFuture<>();
        when(client.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(network);
        var failure = assertThrows(MetadataProviderException.class, () -> MetadataProviderSupport.send(client,
                request(), MetadataRequestContext.create(Duration.ofMillis(25), new AtomicBoolean()), "Provider"));
        assertThat(failure.kind()).isEqualTo(MetadataProviderErrorKind.TIMEOUT);
        assertThat(network.isCancelled()).isTrue();
        reset(client);
        assertThrows(MetadataProviderException.class, () -> MetadataProviderSupport.send(client, request(),
                MetadataRequestContext.create(Duration.ofSeconds(1), new AtomicBoolean(true)), "Provider"));
        verifyNoInteractions(client);
    }

    private static HttpRequest request() { return HttpRequest.newBuilder(URI.create("https://example.invalid/books")).build(); }
}
