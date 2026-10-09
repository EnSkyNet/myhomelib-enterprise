package com.myhomelibcorp.organizer.metadata;

/**
 * Declares how much metadata Organizer can obtain locally for a detected format.
 * Scanner support and structured-metadata support are intentionally separate contracts.
 */
public enum MetadataExtractionLevel {
    STRUCTURED,
    FILENAME_ONLY,
    NOT_APPLICABLE,
    UNCLASSIFIED
}
