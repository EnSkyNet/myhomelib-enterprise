package com.myhomelibcorp.infrastructure.importengine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@Slf4j
public class InpxReader {

    private static final char FIELD_DELIMITER = (char) 4;
    private static final String FALLBACK_DELIMITER = "|";

    public Iterator<Object[]> read(Path file) {
        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry inpEntry = findInpEntry(zipFile);
            if (inpEntry == null) {
                throw new IllegalArgumentException("INP entry not found in " + file);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zipFile.getInputStream(inpEntry), StandardCharsets.UTF_8),
                    65536 // 64KB buffer
            );
            return new InpxIterator(reader, zipFile);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read INPX: " + file, e);
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