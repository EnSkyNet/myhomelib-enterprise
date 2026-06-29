package com.myhomelibcorp.application.usecase.index;

import com.myhomelibcorp.application.port.out.IndexRebuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RebuildIndexUseCase {

    private final IndexRebuilder indexRebuilder;

    public void execute() {
        indexRebuilder.rebuildIndex();
    }

    public int getIndexedDocumentCount() {
        return indexRebuilder.getIndexedDocumentCount();
    }
}