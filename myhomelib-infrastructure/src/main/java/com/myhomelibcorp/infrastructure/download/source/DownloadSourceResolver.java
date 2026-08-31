package com.myhomelibcorp.infrastructure.download.source;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.download.scenario.DownloadScenarioCommand;
import com.myhomelibcorp.infrastructure.download.scenario.DownloadScenarioParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Визначає режим завантаження на основі конфігурації колекції.
 * <p>
 * Логіка:
 * <ol>
 *   <li>Якщо ConnectionScript містить команди GET або POST → CONNECTION_SCRIPT</li>
 *   <li>Якщо Collection.url не порожній → DIRECT_HTTP</li>
 *   <li>Інакше → INVALID_CONFIGURATION</li>
 * </ol>
 * </p>
 */
@Component
@Slf4j
public class DownloadSourceResolver {

    /**
     * Розв'язує режим завантаження для заданої колекції.
     *
     * @param collection колекція
     * @return режим завантаження
     * @throws IllegalStateException якщо неможливо визначити джерело завантаження
     */
    public DownloadMode resolve(Collection collection) {
        return resolve(collection, null);
    }

    /**
     * Розв'язує режим завантаження з попередньо розпарсеними командами.
     *
     * @param collection колекція
     * @param parsedCommands попередньо розпарсені команди (може бути null)
     * @return режим завантаження
     * @throws IllegalStateException якщо неможливо визначити джерело завантаження
     */
    public DownloadMode resolve(Collection collection, List<DownloadScenarioCommand> parsedCommands) {
        String collectionId = collection != null ? collection.getId() : "unknown";
        String collectionName = collection != null ? collection.getName() : "unknown";

        log.debug("Resolving download source for collection: {} ({})", collectionName, collectionId);

        // 1. Якщо є розпарсені команди - використовуємо їх
        List<DownloadScenarioCommand> commands = parsedCommands;
        if (commands == null && collection != null && collection.getConnectionScript() != null) {
            try {
                commands = DownloadScenarioParser.parse(collection.getConnectionScript());
                log.debug("Parsed ConnectionScript for collection {}: {} commands",
                        collectionId, commands != null ? commands.size() : 0);
            } catch (Exception e) {
                log.warn("Failed to parse ConnectionScript for collection {}: {}", collectionId, e.getMessage());
                commands = List.of();
            }
        }

        // Перевірка на наявність команд GET або POST
        boolean hasGet = hasCommand(commands, DownloadScenarioCommand.Type.GET);
        boolean hasPost = hasCommand(commands, DownloadScenarioCommand.Type.POST);

        log.debug("Script has GET: {}, has POST: {}", hasGet, hasPost);

        // 2. Якщо є GET або POST → CONNECTION_SCRIPT
        if (hasGet || hasPost) {
            log.info("Download mode resolved: CONNECTION_SCRIPT for collection {} (has GET/POST)", collectionId);
            return DownloadMode.CONNECTION_SCRIPT;
        }

        // 3. Перевірка наявності URL
        String url = collection != null ? collection.getUrl() : null;
        if (url != null && !url.isBlank()) {
            log.info("Download mode resolved: DIRECT_HTTP for collection {} (URL present, no executable script)", collectionId);
            return DownloadMode.DIRECT_HTTP;
        }

        // 4. Немає ні виконуваного сценарію, ні URL
        String errorMessage = String.format(
                "Не вдалося визначити джерело завантаження для колекції «%s» (id: %s): " +
                        "відсутній Collection.url та виконуваний ConnectionScript (GET/POST).",
                collectionName, collectionId);
        log.error(errorMessage);
        throw new IllegalStateException(errorMessage);
    }

    /**
     * Перевіряє наявність команди заданого типу у списку.
     */
    private boolean hasCommand(List<DownloadScenarioCommand> commands, DownloadScenarioCommand.Type type) {
        if (commands == null || commands.isEmpty()) {
            return false;
        }
        return commands.stream().anyMatch(cmd -> cmd.type() == type);
    }

    /**
     * Швидка перевірка, чи є у сценарії команди GET або POST без повного парсингу.
     */
    public boolean hasNetworkRequestCommand(String script) {
        if (script == null || script.isBlank()) {
            return false;
        }
        return DownloadScenarioParser.hasNetworkRequestCommand(script);
    }
}