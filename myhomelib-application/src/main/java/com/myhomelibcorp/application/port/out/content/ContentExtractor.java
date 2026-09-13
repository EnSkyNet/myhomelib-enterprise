package com.myhomelibcorp.application.port.out.content;

import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ExtractedContent;

import java.io.IOException;

/** Adapter SPI for extracting searchable book contents without coupling application to a reader/parser implementation. */
public interface ContentExtractor {
    String id();
    boolean supports(String format);
    ExtractedContent extract(ContentExtractionRequest request, ContentExtractionContext context) throws IOException;
}
