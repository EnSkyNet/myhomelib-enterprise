package com.myhomelibcorp.application.search;

/**
 * Controlled application error used when the derived search index is not safe to query yet.
 * The caller should keep the library usable and tell the user that search is rebuilding/synchronizing.
 */
public class SearchIndexUnavailableException extends IllegalStateException {
    public SearchIndexUnavailableException(String message) {
        super(message);
    }
}
