package com.myhomelibcorp.converter.toolchain.engine.libmobi;

import com.myhomelibcorp.converter.toolchain.ConversionEngineId;
import com.myhomelibcorp.converter.toolchain.ConversionStep;

import java.util.List;
import java.util.Set;

public final class LibMobiCapabilities {
    private static final Set<String> SOURCES = Set.of("mobi", "prc", "azw", "azw3", "azw4");
    private LibMobiCapabilities() { }

    public static List<ConversionStep> all() {
        return SOURCES.stream().sorted()
                .map(source -> new ConversionStep(ConversionEngineId.LIBMOBI, source, "epub", ".epub", 100, false, 10))
                .toList();
    }
}
