package com.myhomelibcorp.epub.optimizer.graph;

import java.util.Set;

public record EpubResourceNode(String path,
                               String mediaType,
                               long size,
                               String sha256,
                               boolean manifestItem,
                               boolean spineItem,
                               boolean navigation,
                               boolean encrypted,
                               Set<String> properties) {
    public EpubResourceNode {
        properties = properties == null ? Set.of() : Set.copyOf(properties);
    }
}
