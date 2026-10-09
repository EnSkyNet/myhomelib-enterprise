package com.myhomelibcorp.application.annotation.remote;

public record ReadwiseExportResult(long scanned, long exported, long unchanged, int batches) { }
