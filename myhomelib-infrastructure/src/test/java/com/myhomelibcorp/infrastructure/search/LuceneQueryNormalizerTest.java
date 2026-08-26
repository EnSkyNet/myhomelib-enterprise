package com.myhomelibcorp.infrastructure.search;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneQueryNormalizerTest {
    private LuceneQueryNormalizer normalizer() {
        var analyzer = new StandardAnalyzer();
        var parser = new MultiFieldQueryParser(new String[]{"title", "authors", "year", "created"}, analyzer);
        parser.setAllowLeadingWildcard(true);
        return new LuceneQueryNormalizer(parser);
    }

    @Test
    void preservesClassicAliasesComparisonsAndContainsSyntax() {
        var n = normalizer();
        assertThat(n.normalizeClassicSearchSyntax("автор:Франко")).isEqualTo("authors:Франко");
        assertThat(n.normalizeClassicSearchSyntax("year>=2023")).isEqualTo("year:[2023 TO *]");
        assertThat(n.normalizeClassicSearchSyntax("added<2025-01-01")).isEqualTo("created:[* TO 20250101}");
        assertThat(n.normalizeClassicSearchSyntax("%істор%")).isEqualTo("*істор*");
    }
}
