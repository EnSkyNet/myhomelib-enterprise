package com.myhomelibcorp.converter.toolchain.engine.fbc;

import com.myhomelibcorp.converter.toolchain.ConversionEngineId;
import com.myhomelibcorp.converter.toolchain.ConversionStep;

import java.util.List;

public final class FbcCapabilities {
    private FbcCapabilities() { }

    public static List<ConversionStep> all() {
        return List.of(
                new ConversionStep(ConversionEngineId.FBC, "fb2", "epub", ".epub", 100, false, 10),
                new ConversionStep(ConversionEngineId.FBC, "fb2", "kepub", ".kepub", 100, false, 10),
                new ConversionStep(ConversionEngineId.FBC, "fb2", "pdf", ".pdf", 100, true, 20),
                new ConversionStep(ConversionEngineId.FBC, "fb2", "txt", ".txt", 100, true, 10),
                new ConversionStep(ConversionEngineId.FBC, "fb2_zip", "epub", ".epub", 100, false, 12),
                new ConversionStep(ConversionEngineId.FBC, "fb2_zip", "kepub", ".kepub", 100, false, 12),
                new ConversionStep(ConversionEngineId.FBC, "fb2_zip", "pdf", ".pdf", 100, true, 22),
                new ConversionStep(ConversionEngineId.FBC, "fb2_zip", "txt", ".txt", 100, true, 12)
        );
    }
}
