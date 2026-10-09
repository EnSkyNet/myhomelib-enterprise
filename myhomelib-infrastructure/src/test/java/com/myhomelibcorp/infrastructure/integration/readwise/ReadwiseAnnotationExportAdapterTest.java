package com.myhomelibcorp.infrastructure.integration.readwise;

import com.myhomelibcorp.application.annotation.remote.RemoteAnnotationItem;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.myhomelibcorp.shared.security.SecretStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReadwiseAnnotationExportAdapterTest {
    @Test
    void mapsStableHighlightUrlAndNeverPersistsTokenInRequestBody() throws Exception {
        HttpClient http=mock(HttpClient.class); @SuppressWarnings("unchecked") HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn("[]".getBytes());
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var store=new MemoryStore(); store.write(ReadwiseAnnotationExportAdapter.TOKEN_KEY,"super-secret-token");
        var adapter=new ReadwiseAnnotationExportAdapter(new ObjectMapper(),mock(QueryExecutor.class),mock(CollectionManager.class),http,x->{},store);
        var result=adapter.exportBatch(List.of(item("ann/1")));
        assertThat(result.acceptedAnnotationIds()).containsExactly("ann/1");
        var cap=org.mockito.ArgumentCaptor.forClass(HttpRequest.class); verify(http).send(cap.capture(),any(HttpResponse.BodyHandler.class));
        HttpRequest req=cap.getValue();
        assertThat(req.headers().firstValue("Authorization")).contains("Token super-secret-token");
        var publisher=req.bodyPublisher().orElseThrow();
        assertThat(publisher.contentLength()).isPositive();
        assertThat(req.uri()).isEqualTo(ReadwiseAnnotationExportAdapter.HIGHLIGHTS);
        assertThat(req.toString()).doesNotContain("super-secret-token");
    }

    @Test
    void authFailureIsRedactedAndDoesNotExposeToken() throws Exception {
        HttpClient http=mock(HttpClient.class); @SuppressWarnings("unchecked") HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401); when(response.body()).thenReturn(new byte[0]);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var store=new MemoryStore(); store.write(ReadwiseAnnotationExportAdapter.TOKEN_KEY,"secret-token");
        var adapter=new ReadwiseAnnotationExportAdapter(new ObjectMapper(),mock(QueryExecutor.class),mock(CollectionManager.class),http,x->{},store);
        assertThatThrownBy(adapter::testConnection).isInstanceOf(SecurityException.class)
                .hasMessageContaining("authentication").hasMessageNotContaining("secret-token");
    }

    @Test
    void timeoutIsBoundedAndRedacted() throws Exception {
        HttpClient http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.net.http.HttpTimeoutException("simulated timeout"));
        var store=new MemoryStore(); store.write(ReadwiseAnnotationExportAdapter.TOKEN_KEY,"secret-timeout-token");
        var adapter=new ReadwiseAnnotationExportAdapter(new ObjectMapper(),mock(QueryExecutor.class),mock(CollectionManager.class),http,x->{},store);
        assertThatThrownBy(() -> adapter.exportBatch(List.of(item("timeout"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("network request failed")
                .hasMessageNotContaining("secret-timeout-token");
        verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }

    @Test
    void rateLimitRetriesOnlyIdempotentHighlightPost() throws Exception {
        HttpClient http=mock(HttpClient.class); @SuppressWarnings("unchecked") HttpResponse<byte[]> limited=mock(HttpResponse.class); @SuppressWarnings("unchecked") HttpResponse<byte[]> ok=mock(HttpResponse.class);
        when(limited.statusCode()).thenReturn(429); when(limited.body()).thenReturn(new byte[0]); when(limited.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of("1")),(a,b)->true));
        when(ok.statusCode()).thenReturn(200); when(ok.body()).thenReturn("[]".getBytes()); when(ok.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(limited,ok);
        AtomicInteger sleeps=new AtomicInteger(); var store=new MemoryStore(); store.write(ReadwiseAnnotationExportAdapter.TOKEN_KEY,"token");
        var adapter=new ReadwiseAnnotationExportAdapter(new ObjectMapper(),mock(QueryExecutor.class),mock(CollectionManager.class),http,x->sleeps.incrementAndGet(),store);
        assertThat(adapter.exportBatch(List.of(item("a1"))).acceptedAnnotationIds()).contains("a1");
        verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)); assertThat(sleeps).hasValue(1);
    }

    private static RemoteAnnotationItem item(String id){return new RemoteAnnotationItem(id,"b","Book","Author","quote","note",List.of(),0.4,Instant.parse("2026-10-01T00:00:00Z"),"fp");}
    private static final class MemoryStore implements SecretStore {
        final Map<String,String> m=new HashMap<>(); public Optional<String> read(String k){return Optional.ofNullable(m.get(k));} public void write(String k,String v){m.put(k,v);} public void delete(String k){m.remove(k);} public String backendId(){return "test";}
    }
}
