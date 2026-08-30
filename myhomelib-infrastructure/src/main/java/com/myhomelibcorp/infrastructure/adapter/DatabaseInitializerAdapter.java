package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.infrastructure.DatabaseInitializerPort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Compatibility adapter: directory imports only need the canonical migration lifecycle. */
@Component
@RequiredArgsConstructor
public class DatabaseInitializerAdapter implements DatabaseInitializerPort {
    private final DatabaseMigrationPort databaseMigrationPort;

    @Override
    public void initializeCurrentCollection() {
        databaseMigrationPort.migrateCurrentCollection();
    }
}
