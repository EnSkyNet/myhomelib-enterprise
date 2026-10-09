package com.myhomelibcorp.application.comic.duplicates;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public record ComicDuplicateScanControl(BooleanSupplier cancelled, Consumer<ComicDuplicateScanProgress> progress) {
    public ComicDuplicateScanControl {
        cancelled = cancelled == null ? () -> false : cancelled;
        progress = progress == null ? ignored -> { } : progress;
    }
    public static ComicDuplicateScanControl none() { return new ComicDuplicateScanControl(() -> false, ignored -> { }); }
}
