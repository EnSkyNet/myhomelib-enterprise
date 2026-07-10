package com.myhomelibcorp.infrastructure.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class LuceneIndexVersionChecker {

    private final Directory directory;

    /**
     * Повертає версію індексу або -1, якщо індекс порожній або пошкоджений.
     */
    public long getIndexVersion() {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.getVersion();
        } catch (IOException e) {
            log.warn("Не вдалося прочитати версію індексу", e);
            return -1;
        }
    }

    public boolean isIndexEmpty() {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs() == 0;
        } catch (IOException e) {
            return true;
        }
    }

    public int getDocumentCount() {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        } catch (IOException e) {
            return 0;
        }
    }
}