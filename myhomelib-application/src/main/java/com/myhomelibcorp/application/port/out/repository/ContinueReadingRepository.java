package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.dto.ContinueReadingItemDto;

import java.util.List;

/** Read model for the recent/in-progress shelf shared by desktop and web. */
public interface ContinueReadingRepository {
    List<ContinueReadingItemDto> findActive(int limit);
}
