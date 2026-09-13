package com.myhomelibcorp.reader.render.pdf;

import java.util.List;

/** Search result plus explicit text-layer availability for graceful image-only PDF degradation. */
public record PdfSearchOutcome(List<PdfSearchResult> results, boolean textLayerDetected) {
    public PdfSearchOutcome {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
