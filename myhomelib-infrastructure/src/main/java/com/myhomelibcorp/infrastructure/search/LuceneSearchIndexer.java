package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
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
import java.util.List;

@Component
@Slf4j
public class LuceneSearchIndexer implements SearchIndexer {

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

    @Override
    public void indexBook(Book book) {
        if (book == null) return;
        SearchDocument doc = buildSearchDocument(
                book.getId().asString(),
                book.getTitle(),
                book.authorsText(),
                book.getSeries(),
                book.genresText(),
                book.getKeywords(),
                book.getAnnotation()
        );
        indexDocument(doc);
        // Коміт винесено на рівень батча
    }

    @Override
    public void indexSnapshot(BookSnapshot snapshot) {
        if (snapshot == null) return;
        SearchDocument doc = buildSearchDocument(
                snapshot.getId().asString(),
                snapshot.getTitle(),
                snapshot.getAuthorsText(),
                snapshot.getSeries(),
                snapshot.getGenresText(),
                snapshot.getKeywords(),
                snapshot.getAnnotation()
        );
        indexDocument(doc);
        // Коміт винесено на рівень батча
    }

    @Override
    public void indexAll(List<Book> books) {
        if (books == null || books.isEmpty()) return;
        int batchSize = 5000;
        int count = 0;
        for (Book book : books) {
            indexBook(book);
            count++;
            if (count % batchSize == 0) {
                commit();
                log.debug("Commit після {} книг", count);
            }
        }
        commit();
        log.info("Проіндексовано {} книг", books.size());
    }

    @Override
    public void deleteBook(BookId bookId) {
        if (bookId == null) return;
        try {
            indexWriter.deleteDocuments(new Term("id", bookId.asString()));
            commit();
            log.debug("Видалено з індексу: {}", bookId.asString());
        } catch (IOException e) {
            log.error("Помилка видалення з індексу: {}", bookId.asString(), e);
        }
    }

    @Override
    public void rebuildIndex() {
        try {
            indexWriter.deleteAll();
            commit();
            log.info("Індекс очищено");
        } catch (IOException e) {
            log.error("Помилка очищення індексу", e);
        }
    }

    @Override
    public int getDocumentCount() {
        try {
            return indexWriter.getDocStats().numDocs;
        } catch (Exception e) {
            log.error("Помилка отримання кількості документів", e);
            return 0;
        }
    }

    @Override
    public void commit() {
        try {
            if (indexWriter != null) {
                indexWriter.commit();
                log.debug("Lucene індекс закомічено");
            }
        } catch (IOException e) {
            log.error("Помилка commit", e);
        }
    }

    private SearchDocument buildSearchDocument(String id, String title, String authors,
                                               String series, String genres,
                                               String keywords, String annotation) {
        return SearchDocument.builder()
                .id(id)
                .title(title != null ? title : "")
                .authors(authors != null ? authors : "")
                .series(series != null ? series : "")
                .genres(genres != null ? genres : "")
                .keywords(keywords != null ? keywords : "")
                .annotation(annotation != null ? annotation : "")
                .build();
    }

    private void indexDocument(SearchDocument doc) {
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
            log.debug("Індексовано документ: {}", doc.getId());
        } catch (IOException e) {
            log.error("Помилка індексації документа: {}", doc.getId(), e);
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