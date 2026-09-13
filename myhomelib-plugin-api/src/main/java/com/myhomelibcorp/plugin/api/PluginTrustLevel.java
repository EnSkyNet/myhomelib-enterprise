package com.myhomelibcorp.plugin.api;

/** Trust is assigned by the host/user; a plugin cannot self-declare itself trusted. */
public enum PluginTrustLevel {
    TRUSTED,
    UNTRUSTED
}
