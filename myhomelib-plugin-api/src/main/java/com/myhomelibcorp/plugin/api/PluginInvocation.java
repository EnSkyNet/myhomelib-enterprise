package com.myhomelibcorp.plugin.api;

/** Host-controlled invocation boundary for plugin services. */
@FunctionalInterface
public interface PluginInvocation<S, R> {
    R invoke(S service) throws Exception;
}
