package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class CollectionManagementService {

    private final CollectionLifecyclePort collectionLifecyclePort;
    public Collection getCurrentCollection() {
        return collectionLifecyclePort.getCurrentCollection();
    }

    public boolean hasActiveCollection() {
        return collectionLifecyclePort.hasActiveCollection();
    }

    public boolean isCollectionReady() {
        return collectionLifecyclePort.isCollectionReady();
    }

    public long getDatabaseSize() {
        return collectionLifecyclePort.getDatabaseSize();
    }

}