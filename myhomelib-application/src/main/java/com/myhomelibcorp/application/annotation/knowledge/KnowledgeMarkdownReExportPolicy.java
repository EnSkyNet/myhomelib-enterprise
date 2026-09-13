package com.myhomelibcorp.application.annotation.knowledge;

/** Controls collisions for deterministic knowledge-base Markdown exports. */
public enum KnowledgeMarkdownReExportPolicy {
    /** Replace only a file that carries the MyHomeLib ownership marker for the same logical book. */
    REPLACE_MANAGED,
    /** Leave any existing target untouched and count the logical book as skipped. */
    SKIP_EXISTING,
    /** Fail immediately when the deterministic target path already exists. */
    FAIL_IF_EXISTS
}
