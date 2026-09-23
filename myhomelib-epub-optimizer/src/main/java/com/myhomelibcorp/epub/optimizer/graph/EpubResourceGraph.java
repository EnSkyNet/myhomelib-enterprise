package com.myhomelibcorp.epub.optimizer.graph;

import java.util.*;
import java.util.stream.Collectors;

public record EpubResourceGraph(String packagePath,
                                Map<String, EpubResourceNode> nodes,
                                List<EpubResourceReference> references,
                                Set<String> roots,
                                List<String> warnings) {
    public EpubResourceGraph {
        nodes = Map.copyOf(nodes == null ? Map.of() : nodes);
        references = List.copyOf(references == null ? List.of() : references);
        roots = Set.copyOf(roots == null ? Set.of() : roots);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public Set<String> reachable() {
        // OPF manifest membership alone does not make a resource semantically reachable.
        // Reachability is driven by spine/nav/cover/fallback/content/CSS references.
        Map<String, List<String>> outgoing = references.stream()
                .filter(reference -> reference.type() != EpubReferenceType.MANIFEST)
                .collect(Collectors.groupingBy(
                        EpubResourceReference::fromPath,
                        Collectors.mapping(EpubResourceReference::toPath, Collectors.toList())));
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (!visited.add(next)) continue;
            for (String target : outgoing.getOrDefault(next, List.of())) {
                if (nodes.containsKey(target) && !visited.contains(target)) queue.addLast(target);
            }
        }
        return Set.copyOf(visited);
    }

    public Set<String> conservativeOrphans() {
        Set<String> reachable = reachable();
        return nodes.values().stream()
                .filter(EpubResourceNode::manifestItem)
                .filter(node -> !node.encrypted())
                .filter(node -> !node.navigation())
                .filter(node -> !reachable.contains(node.path()))
                .map(EpubResourceNode::path)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> safelyPrunableOrphans() {
        if (!warnings.isEmpty()) return Set.of();
        boolean scripted = nodes.values().stream().anyMatch(node -> {
            String media = node.mediaType() == null ? "" : node.mediaType().toLowerCase(Locale.ROOT);
            return node.properties().contains("scripted") || media.contains("javascript") || media.contains("ecmascript");
        });
        if (scripted) return Set.of();
        Set<String> conservative = conservativeOrphans();
        return nodes.values().stream()
                .filter(node -> conservative.contains(node.path()))
                .filter(node -> {
                    String media = node.mediaType() == null ? "" : node.mediaType().toLowerCase(Locale.ROOT);
                    return media.startsWith("image/") || media.startsWith("font/")
                            || media.startsWith("audio/") || media.startsWith("video/");
                })
                .map(EpubResourceNode::path)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Map<String, List<String>> exactDuplicateGroups() {
        return nodes.values().stream()
                .filter(node -> node.sha256() != null && !node.sha256().isBlank())
                .collect(Collectors.groupingBy(EpubResourceNode::sha256,
                        TreeMap::new, Collectors.mapping(EpubResourceNode::path, Collectors.toList())))
                .entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        e -> e.getValue().stream().sorted().toList()));
    }
}
