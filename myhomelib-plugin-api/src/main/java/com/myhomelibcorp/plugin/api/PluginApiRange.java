package com.myhomelibcorp.plugin.api;

import java.util.Objects;

/** Inclusive supported Plugin API range declared by a plugin. */
public record PluginApiRange(PluginApiVersion min, PluginApiVersion max) {
    public PluginApiRange {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min API version must not exceed max");
        }
    }

    public static PluginApiRange currentMajor() {
        PluginApiVersion current = PluginApiVersion.CURRENT;
        return new PluginApiRange(new PluginApiVersion(current.major(), 0),
                new PluginApiVersion(current.major(), Integer.MAX_VALUE));
    }

    public boolean supports(PluginApiVersion version) {
        Objects.requireNonNull(version, "version");
        return version.compareTo(min) >= 0 && version.compareTo(max) <= 0;
    }
}
