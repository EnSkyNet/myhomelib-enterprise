package com.myhomelibcorp.ui.service;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Context-sensitive built-in help. Markdown is bundled; legacy text remains a fallback. */
@Service
public class HelpService {
    private final LocalizationService localization;
    private final HelpTopicRegistry topics;

    public HelpService(LocalizationService localization, HelpTopicRegistry topics) {
        this.localization = localization;
        this.topics = topics;
    }

    public void showForContext(Window owner, String contextId) {
        show(owner, topics.topicForContext(contextId));
    }

    public void show(Window owner, String topic) {
        String name = topics.isKnownTopic(topic) ? topic : topics.topicForContext(topic);
        String language = localization.language();
        HelpPage page = loadLocalized(language, name);
        if (page == null) page = loadLocalized("uk", name);
        if (page == null) page = loadLocalized("uk", "index");
        String text = page == null ? localization.tr("Довідку не знайдено.") : page.text();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("MyHomeLib — " + localization.tr("Допомога"));
        dialog.setHeaderText(page == null ? null : page.title());
        if (owner != null) dialog.initOwner(owner);
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefSize(900, 650);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    private HelpPage loadLocalized(String language, String topic) {
        String prefix = "uk".equals(language) ? "/help/" : "/help/" + language + "/";
        for (String ext : new String[]{".md", ".txt", ".html"}) {
            String value = load(prefix + topic + ext);
            if (value != null) return new HelpPage(extractTitle(value, topic), value);
        }
        return null;
    }

    private String extractTitle(String value, String fallback) {
        if (value != null) {
            for (String line : value.lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ")) return trimmed.substring(2).trim();
                if (!trimmed.isBlank()) break;
            }
        }
        return fallback;
    }

    private String load(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private record HelpPage(String title, String text) { }
}
