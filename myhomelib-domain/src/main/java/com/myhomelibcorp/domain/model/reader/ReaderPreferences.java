package com.myhomelibcorp.domain.model.reader;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ReaderPreferences {
    @Builder.Default
    String theme = "light";

    @Builder.Default
    String fontFamily = "Georgia";

    @Builder.Default
    double fontSize = 18.0;

    @Builder.Default
    double lineSpacing = 1.6;

    @Builder.Default
    double paragraphSpacing = 1.5;

    @Builder.Default
    double firstLineIndent = 1.5;

    @Builder.Default
    boolean hyphenation = true;

    @Builder.Default
    String alignment = "justify";

    @Builder.Default
    double marginTop = 20;

    @Builder.Default
    double marginBottom = 20;

    @Builder.Default
    double marginLeft = 30;

    @Builder.Default
    double marginRight = 30;

    @Builder.Default
    boolean fullScreen = false;

    @Builder.Default
    boolean autoScroll = false;

    @Builder.Default
    int scrollSpeed = 3;

    @Builder.Default
    boolean showToolbar = true;

    @Builder.Default
    String customCss = "";
}