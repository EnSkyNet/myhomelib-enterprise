package com.myhomelibcorp.reader.core.registry;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookFormatRegistry;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.format.epub.EpubFormat;
import com.myhomelibcorp.reader.format.fb2.Fb2Format;
import com.myhomelibcorp.reader.format.mobi.AzwFormat;
import com.myhomelibcorp.reader.format.mobi.MobiFormat;
import com.myhomelibcorp.reader.format.txt.TxtFormat;
import com.myhomelibcorp.reader.format.zip.ZipFormat;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Locale;

@Slf4j
public class DefaultBookFormatRegistry implements BookFormatRegistry {

    private static final List<BookFormat> STANDARD_FORMATS = List.of(
            new Fb2Format(), new EpubFormat(), new TxtFormat(), new MobiFormat(), new AzwFormat(), new ZipFormat());

    /**
     * Creates a mutable registry preloaded with the built-in Reader formats without replaying
     * four INFO-level registration messages for every Reader/inspection instance. Custom formats
     * may still be registered on the returned registry.
     */
    public static DefaultBookFormatRegistry standard() {
        DefaultBookFormatRegistry registry = new DefaultBookFormatRegistry();
        for (BookFormat format : STANDARD_FORMATS) registry.index(format, false);
        return registry;
    }

    private final ConcurrentMap<String, BookFormat> formatsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BookFormat> formatsByExtension = new ConcurrentHashMap<>();

    @Override
    public Optional<BookFormat> findFormat(BookSource source) {
        if (source == null) {
            return Optional.empty();
        }
        String extension = source.extension();
        if (!extension.isEmpty()) {
            BookFormat byExt = formatsByExtension.get(extension.toLowerCase(Locale.ROOT));
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
        return Optional.ofNullable(formatsByExtension.get(extension.toLowerCase(Locale.ROOT)));
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
        index(format, true);
    }

    private void index(BookFormat format, boolean logRegistration) {
        if (format == null) return;
        formatsById.put(format.id(), format);
        for (String ext : format.extensions()) {
            formatsByExtension.put(ext.toLowerCase(Locale.ROOT), format);
        }
        if (logRegistration) {
            log.info("Зареєстровано формат: {} (розширення: {})",
                    format.displayName(), format.extensions());
        }
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
                formatsByExtension.remove(ext.toLowerCase(Locale.ROOT), format);
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
