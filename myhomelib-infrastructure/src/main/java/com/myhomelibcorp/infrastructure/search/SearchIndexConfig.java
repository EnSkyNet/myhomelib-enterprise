package com.myhomelibcorp.infrastructure.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Paths;

@Configuration
public class SearchIndexConfig {

    @Value("${app.search.index-path:./search-index}")
    private String indexPath;

    @Bean
    public Directory luceneDirectory() throws IOException {
        return FSDirectory.open(Paths.get(indexPath));
    }

    /**
     * Використовуємо StandardAnalyzer для точного пошуку за словами.
     * Він нормалізує текст (приводить до нижнього регістру, видаляє зайві символи),
     * але НЕ розбиває на n-грами.
     */
    @Bean
    public Analyzer luceneAnalyzer() {
        return new StandardAnalyzer();
    }

    /**
     * Багатопольовий парсер для пошуку за кількома полями.
     */
    @Bean
    public QueryParser queryParser(Analyzer analyzer) {
        return new MultiFieldQueryParser(
                new String[]{"title", "authors", "series", "genres", "keywords", "annotation"},
                analyzer
        );
    }
}