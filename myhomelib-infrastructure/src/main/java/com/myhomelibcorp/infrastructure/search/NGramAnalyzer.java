package com.myhomelibcorp.infrastructure.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.ngram.NGramTokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

public class NGramAnalyzer extends Analyzer {

    private static final int MIN_GRAM = 2;
    private static final int MAX_GRAM = 20;

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        StandardTokenizer tokenizer = new StandardTokenizer();
        TokenStream stream = new LowerCaseFilter(tokenizer);
        // Для Lucene 9.9.1 використовуємо конструктор з 4 параметрами
        // (додаємо false, щоб не зберігати оригінальний токен окремо)
        stream = new NGramTokenFilter(stream, MIN_GRAM, MAX_GRAM, false);
        return new TokenStreamComponents(tokenizer, stream);
    }
}