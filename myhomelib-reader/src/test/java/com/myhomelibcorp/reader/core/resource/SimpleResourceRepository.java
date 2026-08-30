package com.myhomelibcorp.reader.core.resource;

import com.myhomelibcorp.reader.api.ResourceInfo;
import com.myhomelibcorp.reader.api.ResourceRepository;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Спрощена реалізація ResourceRepository для невеликих книг.
 * Зберігає всі ресурси в пам'яті.
 */
public class SimpleResourceRepository implements ResourceRepository {

    private final Map<String, ResourceInfo> infos = new LinkedHashMap<>();
    private final Map<String, byte[]> data = new LinkedHashMap<>();

    @Override
    public Optional<ResourceInfo> getInfo(String id) {
        return Optional.ofNullable(infos.get(id));
    }

    @Override
    public Optional<InputStream> open(String id) {
        byte[] bytes = data.get(id);
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        return Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public Iterable<String> getAllIds() {
        return Collections.unmodifiableSet(infos.keySet());
    }

    @Override
    public int count() {
        return infos.size();
    }

    @Override
    public long totalSize() {
        long total = 0;
        for (byte[] bytes : data.values()) {
            total += bytes.length;
        }
        return total;
    }

    public void add(String id, String mimeType, byte[] data) {
        if (id == null || data == null || data.length == 0) {
            return;
        }
        this.infos.put(id, new ResourceInfo(id, mimeType, 0, data.length, mimeType != null && mimeType.startsWith("image/")));
        this.data.put(id, data);
    }

    public void addImage(String id, String mimeType, byte[] data) {
        add(id, mimeType != null ? mimeType : "image/jpeg", data);
    }

    public void remove(String id) {
        infos.remove(id);
        data.remove(id);
    }

    public void clear() {
        infos.clear();
        data.clear();
    }

    public boolean hasData(String id) {
        return data.containsKey(id);
    }

    @Override
    public boolean exists(String id) {
        return infos.containsKey(id) && data.containsKey(id);
    }

    @Override
    public String toString() {
        return "SimpleResourceRepository{" +
                "resources=" + data.size() +
                ", totalSize=" + totalSize() +
                '}';
    }
}