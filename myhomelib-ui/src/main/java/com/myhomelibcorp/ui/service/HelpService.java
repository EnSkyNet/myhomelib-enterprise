package com.myhomelibcorp.ui.service;

import javafx.scene.control.*;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Context-sensitive built-in help. Ukrainian, English and Bulgarian are bundled. */
@Service
public class HelpService {
    private static final Set<String> KNOWN = Set.of(
            "index","collections","inpx","import","search","reader","export","device",
            "settings","portable","hlc2","archives","mcp"
    );

    private final LocalizationService localization;

    public HelpService(LocalizationService localization) { this.localization = localization; }

    public void show(Window owner, String topic) {
        String requested = topic == null ? "index" : topic.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        String name = KNOWN.contains(requested) ? requested : "index";
        String language = localization.language();
        String resource = "uk".equals(language) ? "/help/" + name + ".txt" : "/help/" + language + "/" + name + ".txt";
        String text = load(resource);
        if (text == null) text = load("/help/" + name + ".txt");
        if (text == null) text = localization.tr("Довідку не знайдено.");

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("MyHomeLib — " + localization.tr("Допомога"));
        d.setHeaderText(null);
        if (owner != null) d.initOwner(owner);
        TextArea area = new TextArea(text);
        area.setEditable(false); area.setWrapText(true); area.setPrefSize(840, 620);
        d.getDialogPane().setContent(area);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.setResizable(true);
        d.showAndWait();
    }

    private String load(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Help read error: " + e.getMessage();
        }
    }
}
