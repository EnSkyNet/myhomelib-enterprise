package com.myhomelibcorp.reader.format.zip;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.format.fb2.Fb2StreamingParser;
import com.myhomelibcorp.reader.format.epub.EpubParser;
import com.myhomelibcorp.reader.format.txt.TxtParser;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public class ZipParser implements BookParser {

    private static final Charset[] CHARSETS = {
            StandardCharsets.UTF_8,
            Charset.forName("CP866"),
            Charset.forName("Windows-1251"),
            Charset.forName("IBM866"),
            Charset.forName("KOI8-R")
    };



    @Override
    public ReaderDocument parse(BookSource source, ParseOptions options) throws IOException {
        log.info("📦 Парсинг ZIP: {}", source.name());

        Exception lastException = null;
        for (Charset charset : CHARSETS) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP parsing cancelled");
            try {
                ReaderDocument result = parseWithCharset(source, charset, options);
                if (result != null && !result.isEmpty()) {
                    log.info("✅ ZIP розпарсено з кодуванням: {}", charset);
                    return result;
                }
            } catch (InterruptedIOException e) {
                throw e;
            } catch (Exception e) {
                log.debug("Не вдалося з кодуванням {}: {}", charset, e.getMessage());
                lastException = e;
            }
        }

        throw new IOException("Не вдалося розпарсити ZIP жодним з підтримуваних кодувань", lastException);
    }

    private ReaderDocument parseWithCharset(BookSource source, Charset charset, ParseOptions options)
            throws IOException {

        java.nio.file.Path tempFile = null;
        try {
            tempFile = java.nio.file.Files.createTempFile("zip_parser_", ".zip");
            try (InputStream is = source.openStream(); OutputStream out = java.nio.file.Files.newOutputStream(tempFile)) {
                copyInterruptibly(is, out, ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES);
            }

            try (ZipFile zipFile = new ZipFile(tempFile.toFile(), charset)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                int entryCount = 0;

                while (entries.hasMoreElements()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP parsing cancelled");
                    ZipEntry entry = entries.nextElement();
                    if (++entryCount > ArchiveSafetyLimits.MAX_ENTRY_COUNT)
                        throw new IOException("ZIP contains too many entries");
                    String name = entry.getName().toLowerCase(Locale.ROOT);

                    if (isReaderBook(name)) {
                        log.info("📄 Знайдено книгу в ZIP: {} (кодування імен: {})", entry.getName(), charset);

                        if (entry.getSize() > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                            log.warn("⚠️ Розмір FB2 перевищує ліміт: {} байт", entry.getSize());
                            continue;
                        }

                        // Не тримаємо розпакований FB2 (до 50 MB) як byte[] у heap.
                        // Тимчасовий файл також дозволяє FB2 parser-у безкоштовно
                        // перевідкрити stream для fallback-кодувань.
                        String suffix = name.endsWith(".epub") ? ".epub" :
                                (name.endsWith(".txt") || name.endsWith(".text") || name.endsWith(".md")) ? ".txt" : ".fb2";
                        java.nio.file.Path extractedBook = java.nio.file.Files.createTempFile("myhomelib_zip_book_", suffix);
                        try {
                            boolean tooLarge = false;
                            try (InputStream entryStream = zipFile.getInputStream(entry);
                                 java.io.OutputStream out = java.nio.file.Files.newOutputStream(extractedBook)) {
                                byte[] buffer = new byte[64 * 1024];
                                long total = 0;
                                int read;
                                while ((read = entryStream.read(buffer)) >= 0) {
                                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP parsing cancelled");
                                    if (read == 0) continue;
                                    total += read;
                                    if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                                        tooLarge = true;
                                        break;
                                    }
                                    out.write(buffer, 0, read);
                                }
                            }

                            if (tooLarge) {
                                log.warn("⚠️ Розпакований FB2 перевищує ліміт {} MB", ArchiveSafetyLimits.MAX_ENTRY_BYTES / 1024 / 1024);
                                continue;
                            }

                            BookSource innerSource = new FileBookSource(
                                    extractedBook, source.id() + "!" + entry.getName());
                            BookParser parser = name.endsWith(".epub") ? new EpubParser() :
                                    (name.endsWith(".txt") || name.endsWith(".text") || name.endsWith(".md")) ? new TxtParser() :
                                    new Fb2StreamingParser();

                            try {
                                return parser.parse(innerSource, options);
                            } catch (InterruptedIOException e) {
                                throw e;
                            } catch (Exception e) {
                                log.error("Помилка парсингу {} з ZIP: {}", entry.getName(), e.getMessage());
                                throw new IOException("Не вдалося розпарсити " + entry.getName(), e);
                            }
                        } finally {
                            try {
                                java.nio.file.Files.deleteIfExists(extractedBook);
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
            }

            return null;

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("Помилка парсингу ZIP з кодуванням {}: {}", charset, e.getMessage());
            throw new IOException("Не вдалося розпарсити ZIP з кодуванням " + charset, e);
        } finally {
            if (tempFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.debug("Не вдалося видалити тимчасовий файл: {}", e.getMessage());
                }
            }
        }
    }



    private static void copyInterruptibly(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP parsing cancelled");
            if (read == 0) continue;
            total += read;
            if (maxBytes > 0 && total > maxBytes) throw new IOException("ZIP source exceeds Reader safety limit");
            out.write(buffer, 0, read);
        }
    }

    private boolean isReaderBook(String name) {
        return name.endsWith(".fb2") || name.endsWith(".fbd") || name.endsWith(".epub")
                || name.endsWith(".txt") || name.endsWith(".text") || name.endsWith(".md");
    }

}