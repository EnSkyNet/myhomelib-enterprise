package com.myhomelibcorp.infrastructure.importer.reader;

import com.myhomelibcorp.application.port.out.importer.ImportReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@Slf4j
public class InpxImportReader implements ImportReader {

    private static final char FIELD_DELIMITER = (char) 4;
    private static final String FALLBACK_DELIMITER = "|";

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".inpx") || name.endsWith(".inp");
    }

    @Override
    public Stream<Object[]> read(Path file) {
        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry inpEntry = findInpEntry(zipFile);
            if (inpEntry == null) {
                throw new IllegalArgumentException("INP entry not found in " + file);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zipFile.getInputStream(inpEntry), StandardCharsets.UTF_8),
                    65536
            );
            Iterator<Object[]> iterator = new InpxIterator(reader, zipFile);
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
                    .onClose(() -> {
                        try {
                            reader.close();
                            zipFile.close();
                        } catch (Exception e) {
                            log.warn("Error closing resources", e);
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to read INPX: " + file, e);
        }
    }

    @Override
    public String getFormatName() {
        return "INPX";
    }

    @Override
    public long countBooks(Path file) {
        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            ZipEntry inpEntry = findInpEntry(zipFile);
            if (inpEntry == null) return -1;
            long count = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zipFile.getInputStream(inpEntry), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) count++;
            }
            return count;
        } catch (Exception e) {
            log.warn("Failed to count books in INPX", e);
            return -1;
        }
    }

    private ZipEntry findInpEntry(ZipFile zipFile) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().endsWith(".inp")) {
                return entry;
            }
        }
        return null;
    }

    private static class InpxIterator implements Iterator<Object[]> {
        private final BufferedReader reader;
        private final ZipFile zipFile;
        private String nextLine;
        private boolean finished;

        public InpxIterator(BufferedReader reader, ZipFile zipFile) {
            this.reader = reader;
            this.zipFile = zipFile;
            try {
                this.nextLine = reader.readLine();
            } catch (Exception e) {
                this.finished = true;
            }
        }

        @Override
        public boolean hasNext() {
            return !finished && nextLine != null;
        }

        @Override
        public Object[] next() {
            String line = nextLine;
            try {
                nextLine = reader.readLine();
                if (nextLine == null) {
                    finished = true;
                    reader.close();
                    zipFile.close();
                }
            } catch (Exception e) {
                finished = true;
                throw new RuntimeException(e);
            }
            return splitFields(line);
        }

        private String[] splitFields(String line) {
            List<String> fields = new ArrayList<>(16);
            int start = 0;
            char delimiter = line.indexOf(FIELD_DELIMITER) > 0 ? FIELD_DELIMITER : FALLBACK_DELIMITER.charAt(0);
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == delimiter) {
                    fields.add(line.substring(start, i));
                    start = i + 1;
                }
            }
            fields.add(line.substring(start));
            return fields.toArray(new String[0]);
        }
    }
}