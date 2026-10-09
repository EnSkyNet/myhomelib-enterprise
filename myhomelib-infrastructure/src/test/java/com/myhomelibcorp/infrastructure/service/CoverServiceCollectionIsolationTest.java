package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.cover.CoverCache;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoverServiceCollectionIsolationTest {

    @Test
    void sameBookIdFromDifferentCollectionRootsCannotShareCoverEntry() {
        Map<String, byte[]> storage = new HashMap<>();
        CoverCache cache = new CoverCache() {
            @Override public byte[] get(String key) { return storage.get(key); }
            @Override public void put(String key, byte[] imageData) { storage.put(key, imageData); }
            @Override public void invalidate(String key) { storage.remove(key); }
            @Override public void clear() { storage.clear(); }
        };
        CoverReader reader = book -> book.getCollectionRoot().getBytes(StandardCharsets.UTF_8);
        CoverService service = new CoverService(reader, cache, new RuntimeExtensionRegistry());

        BookDto first = BookDto.builder().id("same-id").collectionRoot("/library/a")
                .folder("books").fileName("same.fb2").build();
        BookDto second = BookDto.builder().id("same-id").collectionRoot("/library/b")
                .folder("books").fileName("same.fb2").build();

        assertThat(new String(service.extractCover(first), StandardCharsets.UTF_8)).isEqualTo("/library/a");
        assertThat(new String(service.extractCover(second), StandardCharsets.UTF_8)).isEqualTo("/library/b");
        assertThat(storage).hasSize(2);
    }
    @Test
    void invalidationRemovesOnlyTheRequestedStorageIdentity() {
        Map<String, byte[]> storage = new HashMap<>();
        CoverCache cache = new CoverCache() {
            @Override public byte[] get(String key) { return storage.get(key); }
            @Override public void put(String key, byte[] imageData) { storage.put(key, imageData); }
            @Override public void invalidate(String key) { storage.remove(key); }
            @Override public void clear() { storage.clear(); }
        };
        CoverReader reader = book -> book.getCollectionRoot().getBytes(StandardCharsets.UTF_8);
        CoverService service = new CoverService(reader, cache, new RuntimeExtensionRegistry());
        BookDto first = BookDto.builder().id("same-id").collectionRoot("/library/a")
                .folder("books").fileName("same.fb2").build();
        BookDto second = BookDto.builder().id("same-id").collectionRoot("/library/b")
                .folder("books").fileName("same.fb2").build();

        service.extractCover(first);
        service.extractCover(second);
        assertThat(storage).hasSize(2);

        service.invalidateCover(first);

        assertThat(storage).hasSize(1);
        assertThat(new String(service.extractCover(second), StandardCharsets.UTF_8)).isEqualTo("/library/b");
    }

}
