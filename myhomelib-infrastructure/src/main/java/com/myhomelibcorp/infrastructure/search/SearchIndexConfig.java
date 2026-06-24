package com.myhomelibcorp.infrastructure.search;

import org.apache.lucene.analysis.Analyzer;
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

    @Bean
    public Analyzer luceneAnalyzer() {
        // Використовуємо NGramAnalyzer для підтримки пошуку за підрядками
        return new NGramAnalyzer();
    }

    @Bean
    public QueryParser queryParser(Analyzer analyzer) {
        // Пошук тільки за полем "authors"
        return new QueryParser("authors", analyzer);
    }
}