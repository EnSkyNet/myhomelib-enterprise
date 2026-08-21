package com.myhomelibcorp.reader.core.registry;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookFormatRegistry;
import com.myhomelibcorp.reader.api.BookSource;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class DefaultBookFormatRegistry implements BookFormatRegistry {

    private final ConcurrentMap<String, BookFormat> formatsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BookFormat> formatsByExtension = new ConcurrentHashMap<>();

    @Override
    public Optional<BookFormat> findFormat(BookSource source) {
        if (source == null) {
            return Optional.empty();
        }
        String extension = source.extension();
        if (!extension.isEmpty()) {
            BookFormat byExt = formatsByExtension.get(extension.toLowerCase());
            if (byExt != null && byExt.supports(source)) {
                return Optional.of(byExt);
            }
        }
        for (BookFormat format : formatsById.values()) {
            if (format.supports(source)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<BookFormat> findByExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(formatsByExtension.get(extension.toLowerCase()));
    }

    @Override
    public Optional<BookFormat> findById(String id) {
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(formatsById.get(id));
    }

    @Override
    public List<BookFormat> getAllFormats() {
        return new ArrayList<>(formatsById.values());
    }

    @Override
    public void register(BookFormat format) {
        if (format == null) {
            return;
        }
        formatsById.put(format.id(), format);
        for (String ext : format.extensions()) {
            formatsByExtension.put(ext.toLowerCase(), format);
        }
        log.info("Зареєстровано формат: {} (розширення: {})",
                format.displayName(), format.extensions());
    }

    public void registerAll(Iterable<BookFormat> formats) {
        for (BookFormat format : formats) {
            register(format);
        }
    }

    public void unregister(String id) {
        BookFormat format = formatsById.remove(id);
        if (format != null) {
            for (String ext : format.extensions()) {
                formatsByExtension.remove(ext.toLowerCase(), format);
            }
            log.info("Видалено формат: {}", format.displayName());
        }
    }

    public boolean isRegistered(String id) {
        return formatsById.containsKey(id);
    }

    public void clear() {
        formatsById.clear();
        formatsByExtension.clear();
        log.info("Реєстр форматів очищено");
    }
}