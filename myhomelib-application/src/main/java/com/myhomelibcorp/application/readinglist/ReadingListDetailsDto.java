package com.myhomelibcorp.application.readinglist;

import java.util.List;

public record ReadingListDetailsDto(ReadingListDto list, List<ReadingListItemDto> items) { }
