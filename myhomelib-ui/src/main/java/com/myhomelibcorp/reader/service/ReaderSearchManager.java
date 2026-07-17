package com.myhomelibcorp.reader.service;

import javafx.scene.web.WebEngine;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;

@Service
@Scope("prototype")
@Slf4j
public class ReaderSearchManager {

    @Getter
    private int matchCount = 0;
    @Getter
    private int currentMatch = 0;

    public void performSearch(WebEngine webEngine, String query, BiConsumer<Integer, Integer> statusUpdater) {
        if (query == null || query.trim().isEmpty()) {
            clearSearch(webEngine, statusUpdater);
            return;
        }
        try {
            webEngine.executeScript("window.find('" + escapeJS(query) + "', false, false, true, false, false, false);");
            String js =
                    "var text = document.body.innerText;" +
                            "var q = '" + escapeJS(query) + "';" +
                            "var count = 0;" +
                            "var pos = text.indexOf(q);" +
                            "while (pos !== -1) {" +
                            "  count++;" +
                            "  pos = text.indexOf(q, pos + q.length);" +
                            "}" +
                            "count;";
            Object result = webEngine.executeScript(js);
            int count = result instanceof Number ? ((Number) result).intValue() : 0;
            matchCount = count;
            currentMatch = count > 0 ? 1 : 0;
            statusUpdater.accept(matchCount, currentMatch);
        } catch (Exception e) {
            log.warn("Search failed: {}", e.getMessage());
            clearSearch(webEngine, statusUpdater);
        }
    }

    public void searchNext(WebEngine webEngine, String query, BiConsumer<Integer, Integer> statusUpdater) {
        if (matchCount == 0) {
            if (query != null && !query.trim().isEmpty()) {
                performSearch(webEngine, query, statusUpdater);
            }
            return;
        }
        if (currentMatch < matchCount) {
            currentMatch++;
        } else {
            currentMatch = 1;
        }
        webEngine.executeScript("window.find('" + escapeJS(query) + "', false, false, true, false, false, false);");
        statusUpdater.accept(matchCount, currentMatch);
    }

    public void searchPrev(WebEngine webEngine, String query, BiConsumer<Integer, Integer> statusUpdater) {
        if (matchCount == 0) return;
        if (currentMatch > 1) {
            currentMatch--;
        } else {
            currentMatch = matchCount;
        }
        webEngine.executeScript("window.find('" + escapeJS(query) + "', false, true, true, false, false, false);");
        statusUpdater.accept(matchCount, currentMatch);
    }

    public void clearSearch(WebEngine webEngine, BiConsumer<Integer, Integer> statusUpdater) {
        webEngine.executeScript("window.getSelection().removeAllRanges();");
        matchCount = 0;
        currentMatch = 0;
        statusUpdater.accept(0, 0);
    }

    private String escapeJS(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}