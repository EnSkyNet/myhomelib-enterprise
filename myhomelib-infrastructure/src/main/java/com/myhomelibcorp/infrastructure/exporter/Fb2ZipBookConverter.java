package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.application.util.SafeFilePublication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Locale;
import java.util.Set;

@Component
@Slf4j
public class Fb2ZipBookConverter implements BookConverter {

    @Override public int priority() { return 1000; }

    @Override
    public Set<BookConversionCapability> capabilities() {
        return Set.of(new BookConversionCapability(Set.of("fb2"), "FB2_ZIP", ".fb2.zip"));
    }

    @Override
    public boolean supports(Book book) {
        return Fb2ConversionSupport.supports(book);
    }

    @Override
    public boolean supports(Book book, String sourceFormat) {
        return "fb2".equals(BookConversionCapability.normalizeFormat(sourceFormat));
    }

    @Override
    public String getTargetExtension() {
        return ".fb2.zip";
    }

    @Override
    public String getFormatName() {
        return "FB2 ZIP";
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        log.debug("Архівація FB2: {}", book.getTitle());

        String entryName = safeEntryName(book);

        try (ZipOutputStream zos = new ZipOutputStream(
                SafeFilePublication.openFreshOutputStream(targetFile, "FB2.ZIP conversion output"))) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            sourceStream.transferTo(zos);
            zos.closeEntry();
        }

        log.info("FB2 ZIP створено: {}", targetFile);
    }
    private static String safeEntryName(Book book) {
        String candidate = book.getArchiveEntry();
        if (candidate == null || candidate.isBlank()) candidate = book.getFileName();
        String leaf = safeLeaf(candidate);
        String lower = leaf.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".fb2") && !lower.endsWith(".fbd")) {
            leaf = safeLeaf(book.getTitle());
            if (leaf.isBlank() || ".".equals(leaf) || "..".equals(leaf)) leaf = "book";
            leaf += ".fb2";
        }
        return leaf;
    }

    /** ZIP output contains exactly one leaf entry; source/catalog paths are never copied verbatim. */
    private static String safeLeaf(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/').strip();
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        StringBuilder safe = new StringBuilder(leaf.length());
        for (int i = 0; i < leaf.length(); i++) {
            char c = leaf.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || Character.isISOControl(c)) safe.append('_');
            else safe.append(c);
        }
        leaf = safe.toString().strip();
        if (leaf.isBlank() || ".".equals(leaf) || "..".equals(leaf)) return "book";
        return leaf;
    }

}
