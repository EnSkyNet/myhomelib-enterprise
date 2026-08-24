package com.myhomelibcorp.application.port.out.download;

import com.myhomelibcorp.domain.model.collection.Collection;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

public interface RemoteCatalogDownloadPort {
    Path download(Collection collection, String url, AtomicBoolean cancel, DoubleConsumer progress) throws Exception;
}
