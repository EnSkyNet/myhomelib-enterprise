package com.myhomelibcorp.application.bulkedit;

/**
 * One deterministic batch edit rule. For SET, value is used. For REGEX_REPLACE, pattern/replacement are used.
 * GENRES SET accepts comma-separated stable genre codes; TAGS maps to BookMetadata.keywords.
 */
public record BatchMetadataEditRule(
        BatchMetadataEditField field,
        BatchMetadataEditAction action,
        String value,
        String pattern,
        String replacement) {
}
