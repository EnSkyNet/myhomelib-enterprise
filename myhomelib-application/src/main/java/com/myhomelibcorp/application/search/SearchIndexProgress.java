package com.myhomelibcorp.application.search;

/** Bounded search-index rebuild telemetry. */
public record SearchIndexProgress(long processed, long total) { }
