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
    private static final int MAX_EXPANSION_ITERATIONS = 10;

    private CommandTemplate() { }

    public static List<String> expand(String template, Map<String, String> values) {
        List<String> args = parse(template);
        if (values == null || values.isEmpty()) return args;
        List<String> expanded = new ArrayList<>(args.size());
        for (String arg : args) {
            String value = expandWithGuard(arg, values);
            expanded.add(value);
        }
        return List.copyOf(expanded);
    }

    /**
     * Expands placeholders in one token with guard against recursive replacement.
     */
    private static String expandWithGuard(String token, Map<String, String> values) {
        String value = token == null ? "" : token;
        if (values == null || values.isEmpty()) return value;

        // Безпечна заміна з обмеженням кількості ітерацій
        for (int iteration = 0; iteration < MAX_EXPANSION_ITERATIONS; iteration++) {
            String previous = value;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isEmpty()) continue;
                String replacement = entry.getValue() == null ? "" : entry.getValue();
                // Замінюємо, але якщо replacement містить ключ, це буде оброблено на наступній ітерації
                value = value.replace(key, replacement);
            }
            // Якщо значення не змінилося - виходимо (немає більше ключів для заміни)
            if (value.equals(previous)) break;
        }
        return value;
    }

    /** Expands placeholders in one already-separated token without re-tokenizing it. */
    public static String expandToken(String token, Map<String, String> values) {
        return expandWithGuard(token == null ? "" : token, values);
    }

    /** Serializes already parsed arguments back to a template without changing token boundaries. */
    public static String formatArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) return "";
        return arguments.stream().map(CommandTemplate::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String quote(String value) {
        if (value == null) return "\"\"";
        if (!value.isEmpty() && value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '\'' || c == '\"')) {
            return value;
        }
        // Backslash is literal in this parser (important for Windows/UNC paths);
        // only an embedded double quote needs escaping inside a double-quoted token.
        return "\"" + value.replace("\"", "\\\"") + "\"";
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
                // This is deliberately not shell escaping. A backslash is literal except
                // when it directly escapes the currently active quote character.
                if (c == '\\' && i + 1 < template.length() && template.charAt(i + 1) == quote) {
                    current.append(quote);
                    i++;
                    tokenStarted = true;
                } else if (c == quote) {
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