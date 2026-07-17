package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.parser.Fb2DomParser;
import com.myhomelibcorp.reader.renderer.DocumentToHtmlConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@Slf4j
public class ReaderContentLoader {

    private static final Charset[] ZIP_CHARSETS = {
            Charset.forName("IBM866"),
            Charset.forName("Windows-1251"),
            Charset.forName("UTF-8"),
            Charset.forName("KOI8-R")
    };

    private final Fb2DomParser fb2Parser = new Fb2DomParser();
    private final DocumentToHtmlConverter htmlConverter = new DocumentToHtmlConverter();

    /**
     * Завантажує вміст книги та повертає HTML для відображення.
     */
    public String loadBookContent(BookDto book) throws Exception {
        BookDocument document;
        String archiveEntry = book.getArchiveEntry();
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            Path archivePath = getArchivePath(book);
            if (archivePath == null || !Files.exists(archivePath)) {
                throw new IllegalArgumentException("Архів не знайдено: " + archivePath);
            }
            try (InputStream is = getEntryStream(archivePath, archiveEntry)) {
                if (is == null) {
                    throw new IllegalArgumentException("Не вдалося прочитати запис з архіву");
                }
                document = fb2Parser.parse(is);
            }
        } else {
            Path bookPath = buildFilePath(book);
            if (!Files.exists(bookPath)) {
                throw new IllegalArgumentException("Файл не знайдено: " + bookPath);
            }
            try (InputStream is = Files.newInputStream(bookPath)) {
                document = fb2Parser.parse(is);
            }
        }
        return htmlConverter.convert(document);
    }

    private Path getArchivePath(BookDto book) {
        String folder = book.getFolder();
        String fileName = book.getFileName();
        String root = book.getCollectionRoot();

        if (folder != null && !folder.isBlank() && isArchivePath(folder)) {
            if (root != null && !root.isBlank() && !Paths.get(folder).isAbsolute()) {
                return Paths.get(root, folder);
            }
            return Paths.get(folder);
        }
        if (fileName != null && !fileName.isBlank() && isArchivePath(fileName)) {
            if (root != null && !root.isBlank() && !Paths.get(fileName).isAbsolute()) {
                return Paths.get(root, fileName);
            }
            return Paths.get(fileName);
        }
        if (folder != null && !folder.isBlank() && fileName != null && !fileName.isBlank()) {
            Path candidate = Paths.get(folder);
            if (isArchivePath(fileName)) {
                candidate = candidate.resolve(fileName);
                if (Files.exists(candidate)) return candidate;
            }
            if (isArchivePath(candidate.toString())) {
                return candidate;
            }
        }
        if (root != null && !root.isBlank()) {
            Path rootPath = Paths.get(root);
            if (folder != null && !folder.isBlank()) {
                Path full = rootPath.resolve(folder);
                if (isArchivePath(full.toString()) && Files.exists(full)) {
                    return full;
                }
                if (fileName != null && !fileName.isBlank()) {
                    full = rootPath.resolve(folder).resolve(fileName);
                    if (isArchivePath(full.toString()) && Files.exists(full)) {
                        return full;
                    }
                }
            } else if (fileName != null && !fileName.isBlank()) {
                Path full = rootPath.resolve(fileName);
                if (isArchivePath(full.toString()) && Files.exists(full)) {
                    return full;
                }
            }
        }
        if (folder != null && !folder.isBlank()) {
            Path p = Paths.get(folder);
            if (isArchivePath(p.toString()) && Files.exists(p)) return p;
        }
        if (fileName != null && !fileName.isBlank()) {
            Path p = Paths.get(fileName);
            if (isArchivePath(p.toString()) && Files.exists(p)) return p;
        }
        return null;
    }

    private boolean isArchivePath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".fb2zip") || lower.endsWith(".fbd");
    }

    private InputStream getEntryStream(Path archivePath, String entryName) {
        for (Charset charset : ZIP_CHARSETS) {
            try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    String simpleName = Paths.get(entryName).getFileName().toString();
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.getName().endsWith(simpleName)) {
                            entry = e;
                            break;
                        }
                    }
                }
                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        return new java.io.ByteArrayInputStream(is.readAllBytes());
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to read archive with charset {}: {}", charset, e.getMessage());
            }
        }
        return null;
    }

    private Path buildFilePath(BookDto book) {
        String fileName = book.getFileName();
        String folder = book.getFolder();
        String root = book.getCollectionRoot();

        if (fileName != null && !fileName.isBlank()) {
            Path fileNamePath = Paths.get(fileName);
            if (fileNamePath.isAbsolute()) {
                return fileNamePath;
            }
        }
        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (folderPath.isAbsolute()) {
                if (fileName != null && !fileName.isBlank()) {
                    return folderPath.resolve(fileName);
                }
                return folderPath;
            }
        }
        if (root != null && !root.isBlank() && folder != null && !folder.isBlank()) {
            Path rootPath = Paths.get(root);
            Path folderPath = Paths.get(folder);
            if (fileName != null && !fileName.isBlank()) {
                return rootPath.resolve(folderPath).resolve(fileName);
            }
            return rootPath.resolve(folderPath);
        }
        if (root != null && !root.isBlank() && fileName != null && !fileName.isBlank()) {
            return Paths.get(root).resolve(fileName);
        }
        if (fileName != null && !fileName.isBlank()) {
            return Paths.get(fileName);
        }
        if (folder != null && !folder.isBlank()) {
            return Paths.get(folder);
        }
        return Paths.get(".");
    }
}