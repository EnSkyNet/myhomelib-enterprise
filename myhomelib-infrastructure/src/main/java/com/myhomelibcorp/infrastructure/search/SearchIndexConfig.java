package com.myhomelibcorp.infrastructure.search;

import org.apache.lucene.analysis.Analyzer;
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

    @Bean
    public Analyzer luceneAnalyzer() {
        return new NGramAnalyzer();
    }

    @Bean
    public QueryParser queryParser(Analyzer analyzer) {
        // ВИПРАВЛЕНО: пошук за кількома полями (замість одного "authors")
        return new MultiFieldQueryParser(
                new String[]{"title", "authors", "series", "genres", "keywords", "annotation"},
                analyzer
        );
    }
}