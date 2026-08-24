package com.myhomelibcorp.application.port.out.exchange;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import java.util.List;

public interface ReadingHistoryPort {
    List<BookId> recent(int limit);
}
