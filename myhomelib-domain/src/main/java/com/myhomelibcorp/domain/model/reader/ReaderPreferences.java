package com.myhomelibcorp.domain.model.reader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Value;

import java.util.Map;

@Value
@Builder(toBuilder = true)
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class ReaderPreferences {
    @Builder.Default String theme = "light";
    @Builder.Default String fontFamily = "Georgia";
    @Builder.Default double fontSize = 18.0;
    @Builder.Default double lineSpacing = 1.6;
    @Builder.Default double paragraphSpacing = 1.5;
    @Builder.Default double firstLineIndent = 1.5;
    @Builder.Default boolean hyphenation = true;
    @Builder.Default String alignment = "justify";
    @Builder.Default double marginTop = 20;
    @Builder.Default double marginBottom = 20;
    @Builder.Default double marginLeft = 30;
    @Builder.Default double marginRight = 30;
    @Builder.Default boolean fullScreen = false;
    @Builder.Default boolean pageMode = false;
    @Builder.Default boolean autoScroll = false;
    @Builder.Default int scrollSpeed = 3;
    @Builder.Default boolean showToolbar = true;
    @Builder.Default String customCss = "";
    @Builder.Default String widthMode = "medium";

    // Stage 19: AlReader-like navigation/status controls. Strings keep domain
    // independent of the reader module while remaining backward compatible
    // with existing JSON preference files.
    @Builder.Default boolean showStatusBar = true;
    @Builder.Default boolean showStatusProgress = true;
    @Builder.Default boolean showStatusChapter = true;
    @Builder.Default boolean showStatusPage = true;
    @Builder.Default String tapLeftAction = "previous-page";
    @Builder.Default String tapCenterAction = "toggle-toolbar";
    @Builder.Default String tapRightAction = "next-page";

    // v7.1 reader parity: real two-page layout and configurable 9-zone/gesture input map.
    @Builder.Default boolean twoPageMode = false;
    @Builder.Default boolean autoTwoPageLandscape = true;
    @Builder.Default boolean showStatusClock = false;
    @Builder.Default boolean pinchZoom = true;
    @Builder.Default Map<String, String> tapActions = Map.of();
    @Builder.Default Map<String, String> longTapActions = Map.of();
    @Builder.Default Map<String, String> gestureActions = Map.of();
}
