package com.myhomelibcorp.application.port.out.contentindexing;

import com.myhomelibcorp.application.content.indexing.ContentIndexingControl;
import com.myhomelibcorp.application.content.indexing.ContentIndexingOutcome;
import com.myhomelibcorp.application.content.indexing.ContentIndexingTask;

public interface ContentIndexingTaskProcessor {
    ContentIndexingOutcome process(ContentIndexingTask task, ContentIndexingControl control);
}
