package com.myhomelibcorp.reader.core.resource;

import com.myhomelibcorp.reader.api.ResourceInfo;
import com.myhomelibcorp.reader.api.ResourceRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ResourceRepository з малим RAM footprint.
 *
 * Невеликі ресурси тримає в обмеженому memory budget, великі — у тимчасових
 * файлах. Таким чином FB2 з десятками ілюстрацій не дублює всі картинки у heap.
 */
public final class HybridResourceRepository implements ResourceRepository, AutoCloseable {

    private static final long DEFAULT_MEMORY_BUDGET = 2L * 1024 * 1024;
    private static final int DEFAULT_IN_MEMORY_ITEM_LIMIT = 256 * 1024;

    private final long memoryBudgetBytes;
    private final int inMemoryItemLimitBytes;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final AtomicLong fileCounter = new AtomicLong();

    private long inMemoryBytes;
    private long totalBytes;
    private Path tempDirectory;
    private boolean closed;

    public HybridResourceRepository() {
        this(DEFAULT_MEMORY_BUDGET, DEFAULT_IN_MEMORY_ITEM_LIMIT);
    }

    public HybridResourceRepository(long memoryBudgetBytes, int inMemoryItemLimitBytes) {
        this.memoryBudgetBytes = Math.max(0, memoryBudgetBytes);
        this.inMemoryItemLimitBytes = Math.max(0, inMemoryItemLimitBytes);
    }

    public synchronized void add(String id, String mimeType, byte[] bytes) {
        ensureOpen();
        if (id == null || id.isBlank() || bytes == null || bytes.length == 0) {
            return;
        }

        removeInternal(id);
        String effectiveMime = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
        ResourceInfo info = new ResourceInfo(id, effectiveMime, 0, bytes.length, effectiveMime.startsWith("image/"));

        if (canKeepInMemory(bytes.length)) {
            entries.put(id, new Entry(info, bytes, null));
            inMemoryBytes += bytes.length;
        } else {
            try {
                Path file = createTempFile();
                Files.write(file, bytes);
                entries.put(id, new Entry(info, null, file));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Не вдалося зберегти великий ресурс Reader у тимчасовому сховищі: " + id, e);
            }
        }
        totalBytes += bytes.length;
    }

    /**
     * Потокове додавання ресурсу без створення повного byte[] у heap.
     * До inMemoryItemLimitBytes дані можуть лишитися в RAM, після перевищення
     * порогу автоматично переходять у temp-файл. maxBytes <= 0 означає без
     * додаткового ліміту. Повертає false, якщо stream перевищив maxBytes.
     */
    public synchronized boolean add(String id, String mimeType, InputStream input, long maxBytes) throws IOException {
        ensureOpen();
        if (id == null || id.isBlank() || input == null) {
            return false;
        }

        String effectiveMime = mimeType == null || mimeType.isBlank()
                ? "application/octet-stream"
                : mimeType;

        ByteArrayOutputStream memory = new ByteArrayOutputStream(
                Math.min(Math.max(1024, inMemoryItemLimitBytes), 64 * 1024));
        Path file = null;
        OutputStream fileOut = null;
        long total = 0;
        byte[] buffer = new byte[64 * 1024];

        try {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if ((maxBytes > 0 && total > maxBytes) || total > Integer.MAX_VALUE) {
                    if (fileOut != null) {
                        fileOut.close();
                        fileOut = null;
                    }
                    deleteQuietly(file);
                    return false;
                }

                if (fileOut == null &&
                        total <= inMemoryItemLimitBytes &&
                        inMemoryBytes + total <= memoryBudgetBytes) {
                    memory.write(buffer, 0, read);
                } else {
                    if (fileOut == null) {
                        file = createTempFile();
                        fileOut = Files.newOutputStream(file);
                        memory.writeTo(fileOut);
                        memory.reset();
                    }
                    fileOut.write(buffer, 0, read);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (fileOut != null) {
                try {
                    fileOut.close();
                } catch (IOException ignored) {
                }
                fileOut = null;
            }
            deleteQuietly(file);
            throw e;
        } finally {
            if (fileOut != null) {
                try {
                    fileOut.close();
                } catch (IOException ignored) {
                }
            }
        }

        if (total <= 0) {
            deleteQuietly(file);
            return false;
        }

        removeInternal(id);
        ResourceInfo info = new ResourceInfo(id, effectiveMime, 0, (int) total, effectiveMime.startsWith("image/"));
        if (file != null) {
            entries.put(id, new Entry(info, null, file));
        } else {
            byte[] bytes = memory.toByteArray();
            entries.put(id, new Entry(info, bytes, null));
            inMemoryBytes += bytes.length;
        }
        totalBytes += total;
        return true;
    }

    /** Реєструє наявність ресурсу без завантаження payload (metadata scan). */
    public synchronized void addMetadata(String id, String mimeType) {
        ensureOpen();
        if (id == null || id.isBlank() || entries.containsKey(id)) {
            return;
        }
        String effectiveMime = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
        ResourceInfo info = new ResourceInfo(id, effectiveMime, 0, 0, effectiveMime.startsWith("image/"));
        entries.put(id, new Entry(info, null, null));
    }

    @Override
    public synchronized Optional<ResourceInfo> getInfo(String id) {
        Entry entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.info);
    }

    @Override
    public synchronized Optional<InputStream> open(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.memory != null && entry.memory.length > 0) {
            return Optional.of(new ByteArrayInputStream(entry.memory));
        }
        if (entry.file != null) {
            try {
                return Optional.of(Files.newInputStream(entry.file));
            } catch (IOException e) {
                throw new UncheckedIOException("Не вдалося відкрити ресурс Reader: " + id, e);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized Iterable<String> getAllIds() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(entries.keySet()));
    }

    @Override
    public synchronized int count() {
        return entries.size();
    }

    @Override
    public synchronized long totalSize() {
        return totalBytes;
    }

    @Override
    public synchronized boolean exists(String id) {
        Entry entry = entries.get(id);
        return entry != null && (entry.memory != null || entry.file != null);
    }

    public synchronized long inMemorySize() {
        return inMemoryBytes;
    }

    public synchronized void clear() {
        for (Entry entry : entries.values()) {
            deleteQuietly(entry.file);
        }
        entries.clear();
        inMemoryBytes = 0;
        totalBytes = 0;
        deleteQuietly(tempDirectory);
        tempDirectory = null;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        clear();
        closed = true;
    }

    private boolean canKeepInMemory(int size) {
        return size <= inMemoryItemLimitBytes && inMemoryBytes + size <= memoryBudgetBytes;
    }

    private Path createTempFile() throws IOException {
        if (tempDirectory == null) {
            tempDirectory = Files.createTempDirectory("myhomelib-reader-");
            tempDirectory.toFile().deleteOnExit();
        }
        Path file = tempDirectory.resolve("resource-" + fileCounter.incrementAndGet() + ".bin");
        file.toFile().deleteOnExit();
        return file;
    }

    private void removeInternal(String id) {
        Entry old = entries.remove(id);
        if (old == null) return;
        if (old.memory != null) inMemoryBytes -= old.memory.length;
        totalBytes -= old.info.length();
        deleteQuietly(old.file);
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Resource repository is closed");
        }
    }

    private record Entry(ResourceInfo info, byte[] memory, Path file) {
    }
}
