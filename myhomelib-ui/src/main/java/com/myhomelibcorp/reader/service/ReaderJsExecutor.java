package com.myhomelibcorp.reader.service;

import javafx.scene.web.WebEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ReaderJsExecutor {

    /**
     * Виконує JavaScript-код і повертає результат як Object.
     */
    public Object executeScript(WebEngine webEngine, String script) {
        if (webEngine == null) {
            log.warn("WebEngine is null, cannot execute script");
            return null;
        }
        try {
            return webEngine.executeScript(script);
        } catch (Exception e) {
            log.error("Error executing JavaScript: {}", script, e);
            return null;
        }
    }

    /**
     * Виконує JavaScript і повертає результат як Integer.
     */
    public Integer executeScriptAsInt(WebEngine webEngine, String script) {
        Object result = executeScript(webEngine, script);
        return result instanceof Number ? ((Number) result).intValue() : null;
    }

    /**
     * Виконує JavaScript і повертає результат як String.
     */
    public String executeScriptAsString(WebEngine webEngine, String script) {
        Object result = executeScript(webEngine, script);
        return result != null ? result.toString() : null;
    }

    /**
     * Виконує JavaScript і повертає результат як Double.
     */
    public Double executeScriptAsDouble(WebEngine webEngine, String script) {
        Object result = executeScript(webEngine, script);
        return result instanceof Number ? ((Number) result).doubleValue() : null;
    }

    /**
     * Виконує JavaScript і повертає результат як Boolean.
     */
    public Boolean executeScriptAsBoolean(WebEngine webEngine, String script) {
        Object result = executeScript(webEngine, script);
        return result instanceof Boolean ? (Boolean) result : null;
    }

    /**
     * Отримує кількість абзаців у документі.
     */
    public int getParagraphCount(WebEngine webEngine) {
        Integer count = executeScriptAsInt(webEngine, "document.querySelectorAll('p[data-paragraph-id]').length");
        return count != null ? count : 0;
    }

    /**
     * Отримує відсоток прокрутки.
     */
    public double getScrollPercent(WebEngine webEngine) {
        String script = "(function() {" +
                "    var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;" +
                "    var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;" +
                "    if (scrollHeight <= 0) return 0;" +
                "    return scrollTop / scrollHeight;" +
                "})();";
        Double result = executeScriptAsDouble(webEngine, script);
        return result != null ? result : 0.0;
    }
}