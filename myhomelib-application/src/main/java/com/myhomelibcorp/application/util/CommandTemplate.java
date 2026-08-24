package com.myhomelibcorp.application.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses an external-command template before substituting metadata placeholders.
 *
 * <p>This is intentionally not a shell parser: commands are executed via
 * {@link ProcessBuilder}, never via cmd.exe/sh. Parsing first means a title,
 * author or path containing whitespace/quotes cannot inject extra arguments.
 * Quote the executable or a literal template argument when it contains spaces.</p>
 */
public final class CommandTemplate {
    private CommandTemplate() { }

    public static List<String> expand(String template, Map<String, String> values) {
        List<String> args = parse(template);
        if (values == null || values.isEmpty()) return args;
        List<String> expanded = new ArrayList<>(args.size());
        for (String arg : args) {
            String value = arg;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isEmpty()) continue;
                value = value.replace(key, entry.getValue() == null ? "" : entry.getValue());
            }
            expanded.add(value);
        }
        return List.copyOf(expanded);
    }

    public static List<String> parse(String template) {
        if (template == null || template.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean tokenStarted = false;

        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                    tokenStarted = true;
                } else {
                    current.append(c);
                    tokenStarted = true;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                tokenStarted = true;
            } else if (Character.isWhitespace(c)) {
                if (tokenStarted) {
                    out.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(c);
                tokenStarted = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException("Незакрита лапка у шаблоні команди");
        if (tokenStarted) out.add(current.toString());
        return List.copyOf(out);
    }
}
