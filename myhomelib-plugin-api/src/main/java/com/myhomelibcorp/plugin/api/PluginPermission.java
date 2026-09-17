package com.myhomelibcorp.plugin.api;

/** Host-visible capabilities that a plugin must declare before it can be enabled. */
public enum PluginPermission {
    NETWORK_ACCESS,
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    EXTERNAL_PROCESS_EXECUTION
}
