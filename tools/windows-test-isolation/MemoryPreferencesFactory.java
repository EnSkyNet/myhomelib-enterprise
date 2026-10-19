package com.myhomelibcorp.testisolation;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Test tooling only. No service registration and no production dependency. */
public final class MemoryPreferencesFactory implements PreferencesFactory {
    private final Preferences user = new Node(null, "");
    private final Preferences system = new Node(null, "");

    @Override public Preferences userRoot() { return user; }
    @Override public Preferences systemRoot() { return system; }

    private static final class Node extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Node> children = new HashMap<>();

        Node(Node parent, String name) { super(parent, name); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() {
            values.clear();
            children.clear();
            // AbstractPreferences removes its own cached child; retain no independent node.
        }
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() {
            children.entrySet().removeIf(entry -> entry.getValue().isRemoved());
            return children.keySet().toArray(String[]::new);
        }
        @Override protected AbstractPreferences childSpi(String name) {
            Node node = children.get(name);
            if (node == null || node.isRemoved()) {
                node = new Node(this, name);
                node.newNode = true;
                children.put(name, node);
            }
            return node;
        }
        @Override protected void syncSpi() { }
        @Override protected void flushSpi() { }
    }
}
