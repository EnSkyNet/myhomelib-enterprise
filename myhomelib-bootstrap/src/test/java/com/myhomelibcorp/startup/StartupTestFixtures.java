package com.myhomelibcorp.startup;

import com.myhomelibcorp.domain.model.collection.Collection;

final class StartupTestFixtures {
    private StartupTestFixtures() { }

    static Collection collection(String id) {
        return new Collection(id, "Library " + id, null, "/tmp/" + id + ".db", 0,
                null, null, null, null);
    }
}
