package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.shared.util.FileNameSupport;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverLocator;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
import com.myhomelibcorp.infrastructure.image.EpubCoverParser;
import com.myhomelibcorp.infrastructure.image.FallbackCoverRenderer;
import com.myhomelibcorp.infrastructure.image.Fb2CoverParser;
import com.myhomelibcorp.infrastructure.image.MobiCoverParser;
import com.myhomelibcorp.infrastructure.image.PdfCoverParser;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverReaderImpl implements CoverReader {

    private final CoverLocator coverLocator;
    private final ZipArchiveReader archiveReader;
    private final Fb2CoverParser fb2CoverParser;
    private final EpubCoverParser epubCoverParser;
    private final MobiCoverParser mobiCoverParser;
    private final PdfCoverParser pdfCoverParser;
    private final FallbackCoverRenderer fallbackCoverRenderer;

    @Override
    public byte[] readCover(BookDto book) {
        if (book == null) return null;
        try {
            Path filePath = coverLocator.locateCoverFile(book).orElse(null);
            if (filePath == null || !Files.exists(filePath)) return null;
            if (archiveReader.isArchive(filePath)) return extractFromArchive(filePath, book);
            return extractFromDocument(filePath, FileNameSupport.extension(filePath.getFileName().toString()), book);
        } catch (Exception e) {
            log.debug("Обкладинка недоступна для {}: {}", book.getId(), e.getMessage());
            return null;
        }
    }

    private byte[] extractFromDocument(Path path, String ext, BookDto book) throws IOException {
        String normalized = ext.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fb2", "fbd" -> {
                try (InputStream in = Files.newInputStream(path)) { yield fb2CoverParser.parseToBytes(in); }
            }
            case "epub" -> epubCoverParser.parse(path);
            case "mobi", "azw", "azw3" -> fallbackIfEmpty(mobiCoverParser.parse(path), normalized, book);
            case "pdf" -> fallbackIfEmpty(pdfCoverParser.parse(path), normalized, book);
            case "djvu", "djv" -> fallbackCoverRenderer.render("DJVU", book.getTitle());
            default -> null;
        };
    }

    private byte[] extractFromArchive(Path archivePath, BookDto book) {
        List<String> entries = archiveReader.listEntries(archivePath);
        if (entries.isEmpty()) return null;

        String targetEntry = findBestEntry(entries, book.getArchiveEntry(), book.getFileName(), book.getTitle());
        if (targetEntry != null) {
            String ext = FileNameSupport.extension(targetEntry);
            try (InputStream in = archiveReader.readEntry(archivePath, targetEntry).orElse(null)) {
                if (in != null) {
                    if (isImageExtension(ext)) return readBounded(in, 24 * 1024 * 1024);
                    if (ext.equals("fb2") || ext.equals("fbd")) return fb2CoverParser.parseToBytes(in);
                    if (isExtraDocument(ext)) {
                        Path temp = materialize(in, ext);
                        try { return extractFromDocument(temp, ext, book); }
                        finally { Files.deleteIfExists(temp); }
                    }
                }
            } catch (Exception e) {
                log.debug("Не вдалося витягти cover з {}!{}: {}", archivePath, targetEntry, e.getMessage());
            }
        }

        try (InputStream image = archiveReader.findFirstEntry(archivePath,
                name -> isImageExtension(FileNameSupport.extension(name))).orElse(null)) {
            return image == null ? null : readBounded(image, 24 * 1024 * 1024);
        } catch (Exception e) {
            return null;
        }
    }

    private String findBestEntry(List<String> entries, String archiveEntry, String fileName, String title) {
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            for (String e : entries) if (sameEntry(e, archiveEntry)) return e;
        }
        if (fileName != null && !fileName.isBlank() && !archiveReader.isArchiveName(fileName)) {
            for (String e : entries) if (sameEntry(e, fileName) || e.endsWith("/" + fileName)) return e;
        }
        if (title != null && !title.isBlank()) {
            String normalizedTitle = title.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
            for (String e : entries) {
                String name = Path.of(e.replace('\\', '/')).getFileName().toString()
                        .toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
                if (name.contains(normalizedTitle) && isSupportedDocument(FileNameSupport.extension(e))) return e;
            }
        }
        for (String e : entries) if (isSupportedDocument(FileNameSupport.extension(e))) return e;
        return null;
    }

    private static boolean sameEntry(String left, String right) {
        if (left == null || right == null) return false;
        String a = left.replace('\\', '/');
        String b = right.replace('\\', '/');
        return a.equalsIgnoreCase(b) || a.endsWith("/" + b);
    }

    private byte[] fallbackIfEmpty(byte[] bytes, String format, BookDto book) {
        return bytes != null && bytes.length > 0 ? bytes : fallbackCoverRenderer.render(format, book.getTitle());
    }

    private static boolean isSupportedDocument(String ext) {
        return switch (ext) {
            case "fb2", "fbd", "epub", "mobi", "azw", "azw3", "pdf", "djvu", "djv" -> true;
            default -> false;
        };
    }

    private static boolean isExtraDocument(String ext) {
        return switch (ext) {
            case "epub", "mobi", "azw", "azw3", "pdf", "djvu", "djv" -> true;
            default -> false;
        };
    }

    private static boolean isImageExtension(String ext) {
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "webp" -> true;
            default -> false;
        };
    }


    private static Path materialize(InputStream in, String ext) throws IOException {
        Path temp = Files.createTempFile("myhomelib-cover-", ext.isBlank() ? ".book" : "." + ext);
        boolean success = false;
        try (OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IOException("Archive entry exceeds cover safety limit");
                out.write(buffer, 0, read);
            }
            success = true;
            return temp;
        } finally {
            if (!success) Files.deleteIfExists(temp);
        }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 64 * 1024));
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > max) throw new IOException("Cover exceeds safe image limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
