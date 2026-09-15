package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;

import java.util.List;

record ReaderAnnotationPresentation(
        List<ReaderAnnotationOverlay> overlays,
        List<ReaderAnnotationUnavailable> unavailable
) {
    ReaderAnnotationPresentation {
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }

    ReaderAnnotationUnavailable unavailable(String id) {
        if (id == null) return null;
        return unavailable.stream().filter(item -> id.equals(item.id())).findFirst().orElse(null);
    }
}
