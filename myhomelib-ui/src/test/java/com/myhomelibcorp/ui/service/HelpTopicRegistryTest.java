package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTopicRegistryTest {
    private final HelpTopicRegistry registry = new HelpTopicRegistry();

    @Test
    void mapsAllCurrentSpecializedWorkspacesToRelevantHelpInsteadOfGenericIndex() {
        assertEquals("navigation", registry.topicForWorkspace("downloaded-author"));
        assertEquals("navigation", registry.topicForWorkspace("publisher"));
        assertEquals("updates", registry.topicForWorkspace("followed-authors"));
        assertEquals("operations", registry.topicForWorkspace("operations"));
        assertEquals("annotations", registry.topicForWorkspace("annotations"));
    }

    @Test
    void exposesSpecializedTopicsAsKnownBundledHelpTopics() {
        assertTrue(registry.isKnownTopic("operations"));
        assertTrue(registry.isKnownTopic("annotations"));
        assertTrue(registry.isKnownTopic("functions"));
    }
}
