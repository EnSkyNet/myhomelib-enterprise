package com.myhomelibcorp.reader.layout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small language-aware dictionary loader plus a conservative syllable fallback.
 * Dictionary lines use visible hyphens (e.g. "бібліо-те-ка"). Positions are
 * source-character boundaries; the source text itself is never modified.
 */
public final class HyphenationService {
    private final Map<String, Map<String, List<Integer>>> cache = new ConcurrentHashMap<>();

    public List<Integer> candidates(String word, String language) {
        if (word == null || word.length() < 5) return List.of();
        String lang = normalizeLanguage(language);
        String key = normalizeWord(word);
        List<Integer> exact = dictionary(lang).get(key);
        if (exact != null && !exact.isEmpty()) return exact;
        return heuristic(word, lang);
    }

    private Map<String, List<Integer>> dictionary(String lang) {
        return cache.computeIfAbsent(lang, this::loadDictionary);
    }

    private Map<String, List<Integer>> loadDictionary(String lang) {
        String resource = "/hyphenation/" + lang + ".dic";
        var stream = HyphenationService.class.getResourceAsStream(resource);
        if (stream == null) return Map.of();
        Map<String, List<Integer>> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String plain = line.replace("-", "");
                List<Integer> positions = new ArrayList<>();
                int pos = 0;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c == '-') {
                        if (pos >= 2 && plain.length() - pos >= 2) positions.add(pos);
                    } else pos++;
                }
                if (!positions.isEmpty()) result.put(normalizeWord(plain), List.copyOf(positions));
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.copyOf(result);
    }

    private List<Integer> heuristic(String word, String lang) {
        String vowels = switch (lang) {
            case "uk" -> "аеєиіїоуюяАЕЄИІЇОУЮЯ";
            case "ru" -> "аеёиоуыэюяАЕЁИОУЫЭЮЯ";
            case "bg" -> "аъеёиоуьюяАЪЕЁИОУЬЮЯ";
            default -> "aeiouyAEIOUY";
        };
        List<Integer> out = new ArrayList<>();
        for (int i = 2; i <= word.length() - 2; i++) {
            char a = word.charAt(i - 1), b = word.charAt(i);
            boolean av = vowels.indexOf(a) >= 0, bv = vowels.indexOf(b) >= 0;
            // Prefer a boundary after a vowel before a consonant when another
            // vowel remains in the suffix. This is deliberately conservative.
            if (av && !bv && containsVowel(word, i + 1, vowels)) out.add(i);
        }
        return List.copyOf(out);
    }

    private boolean containsVowel(String word, int from, String vowels) {
        for (int i = Math.max(0, from); i < word.length(); i++) if (vowels.indexOf(word.charAt(i)) >= 0) return true;
        return false;
    }

    static String normalizeLanguage(String language) {
        String x = language == null ? "" : language.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        if (x.startsWith("uk") || x.startsWith("ua")) return "uk";
        if (x.startsWith("ru")) return "ru";
        if (x.startsWith("bg")) return "bg";
        return "en";
    }

    private String normalizeWord(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
