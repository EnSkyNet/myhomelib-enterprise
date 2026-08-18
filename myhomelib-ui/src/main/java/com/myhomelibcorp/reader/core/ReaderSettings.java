package com.myhomelibcorp.reader.core;

import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class ReaderSettings {
    private String theme = "light";
    private String fontFamily = "Georgia";
    private double fontSize = 18.0;
    private double lineSpacing = 1.6;
    private double paragraphSpacing = 1.5;
    private double firstLineIndent = 1.5;
    private boolean hyphenation = true;
    private String alignment = "justify";
    private double marginTop = 20;
    private double marginBottom = 20;
    private double marginLeft = 30;
    private double marginRight = 30;
    private boolean autoScroll = false;
    private int scrollSpeed = 3;
    private String customCss = "";
    private String widthMode = "medium";
    private boolean pageMode = false;

    public static ReaderSettings createDefault() {
        return new ReaderSettings();
    }

    public void copyFrom(ReaderSettings other) {
        this.theme = other.theme;
        this.fontFamily = other.fontFamily;
        this.fontSize = other.fontSize;
        this.lineSpacing = other.lineSpacing;
        this.paragraphSpacing = other.paragraphSpacing;
        this.firstLineIndent = other.firstLineIndent;
        this.hyphenation = other.hyphenation;
        this.alignment = other.alignment;
        this.marginTop = other.marginTop;
        this.marginBottom = other.marginBottom;
        this.marginLeft = other.marginLeft;
        this.marginRight = other.marginRight;
        this.autoScroll = other.autoScroll;
        this.scrollSpeed = other.scrollSpeed;
        this.customCss = other.customCss;
        this.widthMode = other.widthMode;
        this.pageMode = other.pageMode;
    }

    public ReaderPreferences toDomain() {
        return ReaderPreferences.builder()
                .theme(theme)
                .fontFamily(fontFamily)
                .fontSize(fontSize)
                .lineSpacing(lineSpacing)
                .paragraphSpacing(paragraphSpacing)
                .firstLineIndent(firstLineIndent)
                .hyphenation(hyphenation)
                .alignment(alignment)
                .marginTop(marginTop)
                .marginBottom(marginBottom)
                .marginLeft(marginLeft)
                .marginRight(marginRight)
                .autoScroll(autoScroll)
                .scrollSpeed(scrollSpeed)
                .customCss(customCss)
                .widthMode(widthMode)
                .build();
    }

    public void fromDomain(ReaderPreferences prefs) {
        this.theme = prefs.getTheme();
        this.fontFamily = prefs.getFontFamily();
        this.fontSize = prefs.getFontSize();
        this.lineSpacing = prefs.getLineSpacing();
        this.paragraphSpacing = prefs.getParagraphSpacing();
        this.firstLineIndent = prefs.getFirstLineIndent();
        this.hyphenation = prefs.isHyphenation();
        this.alignment = prefs.getAlignment();
        this.marginTop = prefs.getMarginTop();
        this.marginBottom = prefs.getMarginBottom();
        this.marginLeft = prefs.getMarginLeft();
        this.marginRight = prefs.getMarginRight();
        this.autoScroll = prefs.isAutoScroll();
        this.scrollSpeed = prefs.getScrollSpeed();
        this.customCss = prefs.getCustomCss();
        this.widthMode = prefs.getWidthMode();
    }
}