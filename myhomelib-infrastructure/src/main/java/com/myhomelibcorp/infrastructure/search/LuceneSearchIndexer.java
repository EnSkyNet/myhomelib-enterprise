package com.myhomelibcorp.infrastructure.search;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class LuceneSearchIndexer {

    private final Directory directory;
    private final Analyzer analyzer;
    private IndexWriter indexWriter;

    @Autowired
    public LuceneSearchIndexer(Directory directory, Analyzer analyzer) {
        this.directory = directory;
        this.analyzer = analyzer;
    }

    @PostConstruct
    public void init() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        indexWriter = new IndexWriter(directory, config);
        log.info("Lucene індексатор ініціалізовано з NGramAnalyzer");
    }

    public void indexDocument(SearchDocument doc) {
        try {
            Document luceneDoc = new Document();
            luceneDoc.add(new StringField("id", doc.getId(), Field.Store.YES));
            luceneDoc.add(new TextField("title", doc.getTitle(), Field.Store.YES));
            luceneDoc.add(new TextField("authors", doc.getAuthors(), Field.Store.YES));
            luceneDoc.add(new TextField("series", doc.getSeries(), Field.Store.YES));
            luceneDoc.add(new TextField("genres", doc.getGenres(), Field.Store.YES));
            luceneDoc.add(new TextField("keywords", doc.getKeywords(), Field.Store.YES));
            luceneDoc.add(new TextField("annotation", doc.getAnnotation(), Field.Store.YES));

            indexWriter.updateDocument(new Term("id", doc.getId()), luceneDoc);
            indexWriter.commit();
            log.debug("Індексовано книгу: {}", doc.getId());
        } catch (IOException e) {
            log.error("Помилка індексації книги: {}", doc.getId(), e);
        }
    }

    public void deleteDocument(String id) {
        try {
            indexWriter.deleteDocuments(new Term("id", id));
            indexWriter.commit();
            log.debug("Видалено з індексу: {}", id);
        } catch (IOException e) {
            log.error("Помилка видалення з індексу: {}", id, e);
        }
    }

    public void rebuildIndex() {
        try {
            indexWriter.deleteAll();
            indexWriter.commit();
            log.info("Індекс очищено");
        } catch (IOException e) {
            log.error("Помилка очищення індексу", e);
        }
    }

    public int getDocumentCount() {
        try {
            return indexWriter.getDocStats().numDocs;
        } catch (Exception e) {
            log.error("Помилка отримання кількості документів", e);
            return 0;
        }
    }

    @PreDestroy
    public void close() {
        try {
            if (indexWriter != null) {
                indexWriter.close();
            }
            log.info("Lucene індексатор закрито");
        } catch (IOException e) {
            log.error("Помилка закриття Lucene", e);
        }
    }
}