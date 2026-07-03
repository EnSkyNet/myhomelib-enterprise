package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.event.ImportFinishedEvent;
import com.myhomelibcorp.application.event.ImportSummary;
import jakarta.annotation.PostConstruct;  // <-- ВАЖЛИВО: правильний імпорт
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatisticsEventHandler {

    private final SimpleEventBus eventBus;

    @PostConstruct
    public void init() {
        eventBus.register(ImportFinishedEvent.class, this::handleImportFinished);
        log.info("StatisticsEventHandler зареєстровано");
    }

    private void handleImportFinished(ImportFinishedEvent event) {
        ImportSummary summary = ImportSummary.from(event.source(), event.result());
        log.info("📊 {}", summary.getFormattedMessage());
    }
}