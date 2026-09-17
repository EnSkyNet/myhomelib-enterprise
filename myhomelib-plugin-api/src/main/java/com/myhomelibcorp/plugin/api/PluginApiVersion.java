package com.myhomelibcorp.plugin.api;

/** Semantic version of the public plugin SPI. */
public record PluginApiVersion(int major, int minor) implements Comparable<PluginApiVersion> {
    public static final PluginApiVersion CURRENT = new PluginApiVersion(1, 4);

    public PluginApiVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Plugin API version components must be non-negative");
        }
    }

    @Override
    public int compareTo(PluginApiVersion other) {
        int byMajor = Integer.compare(major, other.major);
        return byMajor != 0 ? byMajor : Integer.compare(minor, other.minor);
    }
}
