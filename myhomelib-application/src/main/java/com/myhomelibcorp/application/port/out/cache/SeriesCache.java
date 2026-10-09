package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;

public interface SeriesCache extends EntityCache<SeriesId, Series> {
}