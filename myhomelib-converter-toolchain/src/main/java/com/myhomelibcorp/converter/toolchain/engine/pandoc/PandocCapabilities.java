package com.myhomelibcorp.converter.toolchain.engine.pandoc;

import com.myhomelibcorp.converter.toolchain.ConversionEngineId;
import com.myhomelibcorp.converter.toolchain.ConversionStep;

import java.util.ArrayList;
import java.util.List;

public final class PandocCapabilities {
    private PandocCapabilities() { }

    public static List<ConversionStep> all() {
        List<ConversionStep> edges = new ArrayList<>();
        add(edges, "epub", "fb2", ".fb2", 100, true, 20);
        add(edges, "epub", "docx", ".docx", 100, true, 20);
        add(edges, "epub", "rtf", ".rtf", 100, true, 20);
        add(edges, "epub", "txt", ".txt", 100, true, 10);
        add(edges, "docx", "epub", ".epub", 100, true, 20);
        add(edges, "docx", "fb2", ".fb2", 90, true, 20);
        add(edges, "rtf", "epub", ".epub", 100, true, 20);
        add(edges, "rtf", "fb2", ".fb2", 90, true, 20);
        add(edges, "html", "epub", ".epub", 100, true, 15);
        add(edges, "htm", "epub", ".epub", 100, true, 15);
        add(edges, "txt", "epub", ".epub", 90, true, 10);
        add(edges, "md", "epub", ".epub", 100, true, 10);
        add(edges, "markdown", "epub", ".epub", 100, true, 10);
        add(edges, "odt", "epub", ".epub", 100, true, 20);
        add(edges, "odt", "docx", ".docx", 100, true, 20);
        add(edges, "odt", "fb2", ".fb2", 90, true, 20);
        return List.copyOf(edges);
    }

    private static void add(List<ConversionStep> edges, String source, String target, String ext,
                            int quality, boolean lossy, int cost) {
        edges.add(new ConversionStep(ConversionEngineId.PANDOC, source, target, ext, quality, lossy, cost));
    }
}
