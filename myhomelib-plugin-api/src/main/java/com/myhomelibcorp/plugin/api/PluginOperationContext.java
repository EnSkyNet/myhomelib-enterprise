package com.myhomelibcorp.plugin.api;

/** Common cancellation/progress contract for plugin operations. */
public interface PluginOperationContext {
    boolean isCancelled();
    void reportProgress(long completed, long total);
}
