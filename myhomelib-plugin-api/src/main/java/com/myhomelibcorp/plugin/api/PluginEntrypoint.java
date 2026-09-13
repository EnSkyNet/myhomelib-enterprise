package com.myhomelibcorp.plugin.api;

import java.util.Map;

/** ServiceLoader entrypoint implemented by each plugin JAR. */
public interface PluginEntrypoint {
    PluginManifest manifest();

    /** One implementation instance per declared service. */
    Map<PluginService, Object> services();
}
