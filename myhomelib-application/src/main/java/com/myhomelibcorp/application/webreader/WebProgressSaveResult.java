package com.myhomelibcorp.application.webreader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import java.util.Objects;

/** Canonical server result returned after online or queued browser progress replay. */
public record WebProgressSaveResult(boolean accepted, ReadingProgressDto canonical) {
    public WebProgressSaveResult {
        canonical = Objects.requireNonNull(canonical, "canonical");
    }
}
