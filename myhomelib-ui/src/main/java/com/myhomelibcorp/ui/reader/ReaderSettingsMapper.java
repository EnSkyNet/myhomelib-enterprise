package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.reader.api.ReaderSettings;

/** Мапінг між domain preferences та UI-незалежними settings reader-engine. */
final class ReaderSettingsMapper {

    private ReaderSettingsMapper() {
    }

    static ReaderSettings fromDomain(ReaderPreferences p) {
        if (p == null) {
            return ReaderSettings.defaultSettings();
        }
        return new ReaderSettings(
                p.getTheme(),
                p.getFontFamily(),
                p.getFontSize(),
                p.getLineSpacing(),
                p.getParagraphSpacing(),
                p.getFirstLineIndent(),
                p.getAlignment(),
                p.getMarginLeft(),
                p.getMarginRight(),
                p.getMarginTop(),
                p.getMarginBottom(),
                p.isHyphenation(),
                p.isPageMode(),
                p.isAutoScroll(),
                p.getScrollSpeed(),
                p.isShowToolbar(),
                p.getCustomCss()
        );
    }

    static ReaderPreferences toDomain(ReaderSettings s, ReaderPreferences previous) {
        String widthMode = previous != null ? previous.getWidthMode() : "medium";
        boolean fullScreen = previous != null && previous.isFullScreen();

        return ReaderPreferences.builder()
                .theme(s.themeName())
                .fontFamily(s.fontFamily())
                .fontSize(s.fontSize())
                .lineSpacing(s.lineSpacing())
                .paragraphSpacing(s.paragraphSpacing())
                .firstLineIndent(s.firstLineIndent())
                .hyphenation(s.hyphenation())
                .alignment(s.alignment())
                .marginTop(s.topMargin())
                .marginBottom(s.bottomMargin())
                .marginLeft(s.leftMargin())
                .marginRight(s.rightMargin())
                .fullScreen(fullScreen)
                .pageMode(s.pageMode())
                .autoScroll(s.autoScroll())
                .scrollSpeed(s.scrollSpeed())
                .showToolbar(s.showToolbar())
                .customCss(s.customCss())
                .widthMode(widthMode)
                .build();
    }
}
