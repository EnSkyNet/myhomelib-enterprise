package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.CacheRefresherPort;
import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheRefresherAdapter implements CacheRefresherPort {

    private final InpxImportPipeline inpxImportPipeline;

    @Override
    public void refreshCachesAsync() {
        inpxImportPipeline.refreshCachesAsync();
    }
}