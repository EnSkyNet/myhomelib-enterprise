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
import org.springframework.context.annotation.Primary;

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
        return new StandardAnalyzer();
    }

    @Bean
    @Primary
    public QueryParser queryParser(Analyzer analyzer) {
        MultiFieldQueryParser parser = new MultiFieldQueryParser(
                new String[]{"title", "authors", "series", "genres", "keywords", "annotation", "file_name", "publisher"},
                analyzer
        );
        // Classic MyHomeLib-style %text% maps to leading/trailing wildcards.
        parser.setAllowLeadingWildcard(true);
        return parser;
    }
}