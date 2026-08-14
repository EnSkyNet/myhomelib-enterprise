package com.myhomelibcorp.reader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@AllArgsConstructor
public class ReaderTheme {
    String name;
    String displayName;
    String background;
    String foreground;
    String secondaryText;
    String linkColor;
    String selectionColor;
    String quoteBackground;
    String quoteBorder;
    String codeBackground;

    public static ReaderTheme light() {
        return ReaderTheme.builder()
                .name("light")
                .displayName("Світла")
                .background("#ffffff")
                .foreground("#111111")
                .secondaryText("#555555")
                .linkColor("#2196F3")
                .selectionColor("#B3D4FC")
                .quoteBackground("#f9f9f9")
                .quoteBorder("#cccccc")
                .codeBackground("#f5f5f5")
                .build();
    }

    public static ReaderTheme sepia() {
        return ReaderTheme.builder()
                .name("sepia")
                .displayName("Селія")
                .background("#f5ecd9")
                .foreground("#331f0a")
                .secondaryText("#6b5a4a")
                .linkColor("#2196F3")
                .selectionColor("#D4C5A9")
                .quoteBackground("#e8dcc8")
                .quoteBorder("#8b7355")
                .codeBackground("#e8dcc8")
                .build();
    }

    public static ReaderTheme dark() {
        return ReaderTheme.builder()
                .name("dark")
                .displayName("Темна")
                .background("#1a1a1a")
                .foreground("#e0e0e0")
                .secondaryText("#888888")
                .linkColor("#64B5F6")
                .selectionColor("#4A4A4A")
                .quoteBackground("#2a2a2a")
                .quoteBorder("#555555")
                .codeBackground("#2a2a2a")
                .build();
    }

    public static ReaderTheme amoled() {
        return ReaderTheme.builder()
                .name("amoled")
                .displayName("AMOLED")
                .background("#000000")
                .foreground("#e0e0e0")
                .secondaryText("#666666")
                .linkColor("#64B5F6")
                .selectionColor("#333333")
                .quoteBackground("#111111")
                .quoteBorder("#333333")
                .codeBackground("#111111")
                .build();
    }

    public static ReaderTheme fromName(String name) {
        return switch (name) {
            case "sepia" -> sepia();
            case "dark" -> dark();
            case "amoled" -> amoled();
            default -> light();
        };
    }
}