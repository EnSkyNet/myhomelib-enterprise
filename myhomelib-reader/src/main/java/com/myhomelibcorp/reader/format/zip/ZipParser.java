package com.myhomelibcorp.reader.format.zip;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.format.fb2.Fb2StreamingParser;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
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

    private static final int MAX_ENTRY_SIZE = 50 * 1024 * 1024; // 50 MB

    @Override
    public BookDocumentMetadata readMetadata(BookSource source) throws IOException {
        return null;
    }

    @Override
    public ReaderDocument parse(BookSource source, ParseOptions options) throws IOException {
        log.info("📦 Парсинг ZIP: {}", source.name());

        Exception lastException = null;
        for (Charset charset : CHARSETS) {
            try {
                ReaderDocument result = parseWithCharset(source, charset, options);
                if (result != null && !result.isEmpty()) {
                    log.info("✅ ZIP розпарсено з кодуванням: {}", charset);
                    return result;
                }
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
            try (InputStream is = source.openStream()) {
                java.nio.file.Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            try (ZipFile zipFile = new ZipFile(tempFile.toFile(), charset)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().toLowerCase();

                    if (name.endsWith(".fb2") || name.endsWith(".fbd")) {
                        log.info("📄 Знайдено FB2 в архіві: {} (кодування: {})", entry.getName(), charset);

                        try (InputStream entryStream = zipFile.getInputStream(entry)) {
                            byte[] data = entryStream.readAllBytes();

                            if (data.length > MAX_ENTRY_SIZE) {
                                log.warn("⚠️ Розмір FB2 перевищує ліміт: {} байт", data.length);
                                continue;
                            }

                            Fb2BookSource fb2Source = new Fb2BookSource(data, entry.getName(), source.id());
                            Fb2StreamingParser fb2Parser = new Fb2StreamingParser();

                            try {
                                return fb2Parser.parse(fb2Source, options);
                            } catch (Exception e) {
                                log.error("Помилка парсингу FB2 з кодуванням {}: {}", charset, e.getMessage());
                                throw new IOException("Не вдалося розпарсити FB2 з кодуванням " + charset, e);
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

    private static class Fb2BookSource implements BookSource {
        private final byte[] data;
        private final String name;
        private final String archiveId;

        public Fb2BookSource(byte[] data, String name, String archiveId) {
            this.data = data;
            this.name = name;
            this.archiveId = archiveId;
        }

        @Override
        public InputStream openStream() {
            return new java.io.ByteArrayInputStream(data);
        }

        @Override
        public OptionalLong size() {
            return OptionalLong.of(data.length);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String extension() {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
        }

        @Override
        public String id() {
            return archiveId + "!" + name;
        }
    }
}