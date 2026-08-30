package com.myhomelibcorp.infrastructure.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SearchIndexConfig {

    /**
     * Bootstrap-only in-memory directory. Persistent indexes are opened per collection by
     * LuceneSearchService under AppPaths.collectionSearchIndexDir(collectionId).
     */
    @Bean
    public Directory luceneDirectory() {
        return new ByteBuffersDirectory();
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
        parser.setAllowLeadingWildcard(true);
        return parser;
    }
}
