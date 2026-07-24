package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.search.SearchEngine;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.index.DirectoryReader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Component
@RequiredArgsConstructor
@Slf4j
public class LuceneSearchEngine implements SearchEngine {

    private final Directory directory;
    private final Analyzer analyzer;
    private final QueryParser queryParser;

    private IndexWriter indexWriter;
    private final ReentrantLock writerLock = new ReentrantLock();

    /**
     * Ліниве створення IndexWriter з синхронізацією.
     */
    private IndexWriter getIndexWriter() throws IOException {
        if (indexWriter == null) {
            writerLock.lock();
            try {
                if (indexWriter == null) {
                    IndexWriterConfig config = new IndexWriterConfig(analyzer);
                    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
                    indexWriter = new IndexWriter(directory, config);
                    log.info("Lucene IndexWriter створено (lazy)");
                }
            } finally {
                writerLock.unlock();
            }
        }
        return indexWriter;
    }

    @Override
    public void index(BookSnapshot snapshot) {
        if (snapshot == null) return;
        try {
            IndexWriter writer = getIndexWriter();
            Document doc = new Document();
            doc.add(new StringField("id", snapshot.getId().asString(), Field.Store.YES));
            doc.add(new TextField("title", snapshot.getTitle() != null ? snapshot.getTitle() : "", Field.Store.YES));
            doc.add(new TextField("authors", snapshot.getAuthorsText() != null ? snapshot.getAuthorsText() : "", Field.Store.YES));
            doc.add(new TextField("series", snapshot.getSeries() != null ? snapshot.getSeries() : "", Field.Store.YES));
            doc.add(new TextField("genres", snapshot.getGenresText() != null ? snapshot.getGenresText() : "", Field.Store.YES));
            doc.add(new TextField("keywords", snapshot.getKeywords() != null ? snapshot.getKeywords() : "", Field.Store.YES));
            doc.add(new TextField("annotation", snapshot.getAnnotation() != null ? snapshot.getAnnotation() : "", Field.Store.YES));
            writer.updateDocument(new Term("id", snapshot.getId().asString()), doc);
        } catch (IOException e) {
            log.error("Failed to index book: {}", snapshot.getId(), e);
        }
    }

    @Override
    public void indexAll(List<BookSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return;
        for (BookSnapshot s : snapshots) {
            index(s);
        }
        commit();
    }

    @Override
    public void delete(BookId bookId) {
        if (bookId == null) return;
        try {
            IndexWriter writer = getIndexWriter();
            writer.deleteDocuments(new Term("id", bookId.asString()));
        } catch (IOException e) {
            log.error("Failed to delete from index: {}", bookId, e);
        }
    }

    @Override
    public void rebuildIndex() {
        try {
            IndexWriter writer = getIndexWriter();
            writer.deleteAll();
            commit();
        } catch (IOException e) {
            log.error("Failed to rebuild index", e);
        }
    }

    @Override
    public void commit() {
        try {
            if (indexWriter != null) {
                indexWriter.commit();
                log.debug("Lucene index committed");
            }
        } catch (IOException e) {
            log.error("Failed to commit index", e);
        }
    }

    @Override
    public SearchResult search(SearchRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return SearchResult.empty();
        }
        long start = System.currentTimeMillis();
        List<BookId> ids = new ArrayList<>();
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            String escapedText = QueryParser.escape(request.text().trim());
            Query query;
            switch (request.mode()) {
                case EXACT -> query = queryParser.parse("\"" + escapedText + "\"");
                case PREFIX -> query = queryParser.parse(escapedText + "*");
                case FUZZY -> query = queryParser.parse(escapedText + "~");
                default -> query = queryParser.parse(escapedText);
            }
            ScoreDoc[] hits = searcher.search(query, request.limit()).scoreDocs;
            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                String id = doc.get("id");
                if (id != null && !id.isEmpty()) {
                    ids.add(BookId.fromString(id));
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            log.debug("Search '{}' found {} results in {} ms", request.text(), ids.size(), elapsed);
            return new SearchResult(ids, ids.size(), request.offset() / request.limit(), request.limit(), elapsed);
        } catch (Exception e) {
            log.error("Search failed: {}", request.text(), e);
            return SearchResult.empty();
        }
    }

    @Override
    public int getDocumentCount() {
        try {
            if (indexWriter != null) {
                return indexWriter.getDocStats().numDocs;
            } else {
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    return reader.numDocs();
                }
            }
        } catch (Exception e) {
            log.error("Failed to get document count", e);
            return 0;
        }
    }

    @Override
    public void clear() {
        try {
            IndexWriter writer = getIndexWriter();
            writer.deleteAll();
            commit();
        } catch (IOException e) {
            log.error("Failed to clear index", e);
        }
    }

    @PreDestroy
    public void close() {
        writerLock.lock();
        try {
            if (indexWriter != null) {
                indexWriter.close();
                log.info("Lucene IndexWriter закрито");
            }
            if (directory != null) {
                directory.close();
                log.info("Lucene Directory закрито");
            }
        } catch (IOException e) {
            log.error("Failed to close Lucene resources", e);
        } finally {
            writerLock.unlock();
        }
    }
}