package com.myhomelibcorp.shared.format;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static com.myhomelibcorp.shared.format.SupportedFormat.Family.ARCHIVE;
import static com.myhomelibcorp.shared.format.SupportedFormat.Family.BOOK;
import static com.myhomelibcorp.shared.format.SupportedFormat.Family.CATALOG;
import static com.myhomelibcorp.shared.format.SupportedFormat.ImportMode.*;

/**
 * Single source of truth for file-format capabilities shared by import, UI, search, persistence and Reader.
 * Extension matching is locale-independent and checks compound suffixes before simple ones.
 */
public final class SupportedFormatRegistry {
    private static final SupportedFormatRegistry STANDARD = new SupportedFormatRegistry(standardFormats());

    private final List<SupportedFormat> formats;
    private final Map<String, SupportedFormat> byId;
    private final List<ExtensionBinding> extensionBindings;

    public SupportedFormatRegistry(List<SupportedFormat> formats) {
        if (formats == null || formats.isEmpty()) throw new IllegalArgumentException("formats must not be empty");
        LinkedHashMap<String, SupportedFormat> ids = new LinkedHashMap<>();
        LinkedHashMap<String, SupportedFormat> extensions = new LinkedHashMap<>();
        for (SupportedFormat format : formats) {
            if (ids.putIfAbsent(format.id(), format) != null) {
                throw new IllegalArgumentException("Duplicate format id: " + format.id());
            }
            for (String extension : format.extensions()) {
                SupportedFormat previous = extensions.putIfAbsent(extension, format);
                if (previous != null) {
                    throw new IllegalArgumentException("Extension '" + extension + "' belongs to both "
                            + previous.id() + " and " + format.id());
                }
            }
        }
        this.formats = List.copyOf(formats);
        this.byId = Collections.unmodifiableMap(ids);
        this.extensionBindings = extensions.entrySet().stream()
                .map(e -> new ExtensionBinding(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt((ExtensionBinding b) -> b.extension().length()).reversed())
                .toList();
    }

    public static SupportedFormatRegistry standard() { return STANDARD; }

    public List<SupportedFormat> all() { return formats; }

    public Optional<SupportedFormat> byId(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<SupportedFormat> detect(Path file) {
        return file == null || file.getFileName() == null ? Optional.empty() : detect(file.getFileName().toString());
    }

    public Optional<SupportedFormat> detect(String fileName) {
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        String name = fileName.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        for (ExtensionBinding binding : extensionBindings) {
            if (name.endsWith("." + binding.extension())) return Optional.of(binding.format());
        }
        return Optional.empty();
    }

    public boolean isImportSupported(Path file) {
        return detect(file).map(SupportedFormat::importSupported).orElse(false);
    }

    public boolean isFormat(Path file, String... ids) {
        Optional<SupportedFormat> detected = detect(file);
        if (detected.isEmpty() || ids == null) return false;
        String actual = detected.get().id();
        for (String id : ids) {
            if (id != null && actual.equals(id.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public String searchFormat(String fileName) {
        return detect(fileName).map(SupportedFormat::searchFormat).orElse("UNKNOWN");
    }

    public Set<String> extensions(Predicate<SupportedFormat> predicate) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (SupportedFormat format : formats) {
            if (predicate.test(format)) result.addAll(format.extensions());
        }
        return Collections.unmodifiableSet(result);
    }

    public Set<String> chooserPatterns(Predicate<SupportedFormat> predicate) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (SupportedFormat format : formats) {
            if (predicate.test(format)) result.addAll(format.chooserPatterns());
        }
        return Collections.unmodifiableSet(result);
    }

    public Set<String> extensionsForIds(String... ids) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) byId(id).ifPresent(format -> result.addAll(format.extensions()));
        }
        return Collections.unmodifiableSet(result);
    }

    private record ExtensionBinding(String extension, SupportedFormat format) { }

    private static List<SupportedFormat> standardFormats() {
        List<SupportedFormat> result = new ArrayList<>();
        result.add(f("fb2", "FB2", "FB2", set("fb2", "fbd"), set("application/x-fictionbook+xml"), BOOK, NATIVE, true, true, true, true));
        result.add(f("epub", "EPUB", "EPUB", set("epub"), set("application/epub+zip"), BOOK, NATIVE, true, true, true, true));
        result.add(f("txt", "TXT", "Text", set("txt", "text", "md"), set("text/plain", "text/markdown"), BOOK, NATIVE, true, true, false, true));
        result.add(f("pdf", "PDF", "PDF", set("pdf"), set("application/pdf"), BOOK, GENERIC, true, false, true, false));
        result.add(f("mobi", "MOBI", "MOBI", set("mobi"), set("application/x-mobipocket-ebook"), BOOK, GENERIC, true, false, true, false));
        result.add(f("azw3", "AZW3", "AZW/AZW3", set("azw", "azw3"), set("application/vnd.amazon.ebook"), BOOK, GENERIC, true, false, true, false));
        result.add(f("djvu", "DJVU", "DjVu", set("djvu", "djv"), set("image/vnd.djvu", "image/x-djvu"), BOOK, GENERIC, true, false, true, false));
        result.add(f("doc", "DOC", "DOC", set("doc"), set("application/msword"), BOOK, GENERIC, false, false, false, false));
        result.add(f("docx", "DOCX", "DOCX", set("docx"), set("application/vnd.openxmlformats-officedocument.wordprocessingml.document"), BOOK, GENERIC, false, false, false, false));
        result.add(f("odt", "ODT", "ODT", set("odt"), set("application/vnd.oasis.opendocument.text"), BOOK, GENERIC, false, false, false, false));
        result.add(f("rtf", "RTF", "RTF", set("rtf"), set("application/rtf", "text/rtf"), BOOK, GENERIC, false, false, false, false));
        result.add(f("html", "HTML", "HTML", set("html", "htm", "xhtml"), set("text/html", "application/xhtml+xml"), BOOK, GENERIC, false, false, false, false));
        result.add(f("chm", "CHM", "CHM", set("chm"), set("application/vnd.ms-htmlhelp"), BOOK, GENERIC, false, false, false, false));
        result.add(f("inpx", "INPX", "INPX/INP", set("inpx", "inp"), set("application/zip", "application/octet-stream"), CATALOG, SupportedFormat.ImportMode.CATALOG, true, false, false, false));
        result.add(f("zip", "ZIP", "ZIP/FB2ZIP", set("fb2.zip", "fb2zip", "zip"), set("application/zip"), ARCHIVE, SupportedFormat.ImportMode.ARCHIVE, true, true, true, true));
        result.add(f("cbz", "CBZ", "CBZ", set("cbz"), set("application/vnd.comicbook+zip"), ARCHIVE, SupportedFormat.ImportMode.ARCHIVE, true, false, false, false));
        result.add(f("jar", "JAR", "JAR", set("jar"), set("application/java-archive"), ARCHIVE, SupportedFormat.ImportMode.ARCHIVE, true, false, false, false));
        result.add(f("7z", "SEVEN_Z", "7Z", set("7z"), set("application/x-7z-compressed"), ARCHIVE, SupportedFormat.ImportMode.ARCHIVE, true, false, false, false));
        result.add(f("rar", "RAR", "RAR", set("rar"), set("application/vnd.rar", "application/x-rar-compressed"), ARCHIVE, SupportedFormat.ImportMode.ARCHIVE, true, false, false, false));
        result.add(f("cbr", "CBR", "CBR", set("cbr"), set("application/vnd.comicbook-rar"), ARCHIVE, SupportedFormat.ImportMode.ARCHIVE, true, false, false, false));
        result.add(f("tar", "TAR", "TAR", set("tar.gz", "tar.bz2", "tar.xz", "tgz", "tbz2", "txz", "tar"), set("application/x-tar"), ARCHIVE, SupportedFormat.ImportMode.ARCHIVE, true, false, false, false));
        result.add(f("cpio", "CPIO", "CPIO", set("cpio"), set("application/x-cpio"), ARCHIVE, SupportedFormat.ImportMode.ARCHIVE, true, false, false, false));
        return List.copyOf(result);
    }

    private static SupportedFormat f(String id, String searchFormat, String displayName, Set<String> extensions,
                                     Set<String> mimeTypes, SupportedFormat.Family family,
                                     SupportedFormat.ImportMode importMode, boolean metadata, boolean reader,
                                     boolean cover, boolean fullText) {
        return new SupportedFormat(id, searchFormat, displayName, extensions, mimeTypes, family, importMode,
                metadata, reader, cover, fullText);
    }

    private static Set<String> set(String... values) {
        return values == null ? Set.of() : Set.of(values);
    }
}
