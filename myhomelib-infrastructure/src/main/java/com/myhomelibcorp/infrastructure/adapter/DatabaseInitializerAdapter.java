package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.infrastructure.DatabaseInitializerPort;
import com.myhomelibcorp.infrastructure.initializer.DatabaseInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseInitializerAdapter implements DatabaseInitializerPort {

    private final DatabaseInitializer databaseInitializer;

    @Override
    public void initializeCurrentCollection() {
        databaseInitializer.initializeCurrentCollection();
    }
}