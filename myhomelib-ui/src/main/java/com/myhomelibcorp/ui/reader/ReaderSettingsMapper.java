package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.domain.model.reader.ReaderElementStylePreferences;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.reader.api.ReaderElementStyle;
import com.myhomelibcorp.reader.api.ReaderInputSettings;
import com.myhomelibcorp.reader.api.ReaderSemanticElement;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderStyleSheet;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Мапінг між domain preferences та UI-незалежними settings reader-engine. */
final class ReaderSettingsMapper {

    private ReaderSettingsMapper() { }

    static ReaderSettings fromDomain(ReaderPreferences p) {
        if (p == null) return ReaderSettings.defaultSettings();
        ReaderInputSettings input = inputFromDomain(p);
        ReaderStyleSheet styleSheet = styleSheetFromDomain(p.getSemanticStyles());
        return new ReaderSettings(
                p.getTheme(), p.getFontFamily(), p.getFontSize(), p.getLineSpacing(),
                p.getParagraphSpacing(), p.getFirstLineIndent(), p.getAlignment(),
                p.getMarginLeft(), p.getMarginRight(), p.getMarginTop(), p.getMarginBottom(),
                p.isHyphenation(), p.isPageMode(), p.isAutoScroll(), p.getScrollSpeed(),
                p.isShowToolbar(), p.getCustomCss(), p.isShowStatusBar(),
                p.isShowStatusProgress(), p.isShowStatusChapter(), p.isShowStatusPage(),
                input.middleLeft(), input.middleCenter(), input.middleRight(),
                p.isTwoPageMode(), p.isAutoTwoPageLandscape(), p.isShowStatusClock(), input,
                styleSheet
        );
    }

    static ReaderPreferences toDomain(ReaderSettings s, ReaderPreferences previous) {
        String widthMode = previous != null ? previous.getWidthMode() : "medium";
        boolean fullScreen = previous != null && previous.isFullScreen();
        ReaderInputSettings input = s.input() != null ? s.input() : ReaderInputSettings.defaults();

        return ReaderPreferences.builder()
                .theme(s.themeName()).fontFamily(s.fontFamily()).fontSize(s.fontSize())
                .lineSpacing(s.lineSpacing()).paragraphSpacing(s.paragraphSpacing())
                .firstLineIndent(s.firstLineIndent()).hyphenation(s.hyphenation()).alignment(s.alignment())
                .marginTop(s.topMargin()).marginBottom(s.bottomMargin()).marginLeft(s.leftMargin()).marginRight(s.rightMargin())
                .fullScreen(fullScreen).pageMode(s.pageMode()).autoScroll(s.autoScroll()).scrollSpeed(s.scrollSpeed())
                .showToolbar(s.showToolbar()).customCss(s.customCss()).widthMode(widthMode)
                .showStatusBar(s.showStatusBar()).showStatusProgress(s.showStatusProgress())
                .showStatusChapter(s.showStatusChapter()).showStatusPage(s.showStatusPage())
                .tapLeftAction(input.middleLeft()).tapCenterAction(input.middleCenter()).tapRightAction(input.middleRight())
                .twoPageMode(s.twoPageMode()).autoTwoPageLandscape(s.autoTwoPageLandscape())
                .showStatusClock(s.showStatusClock()).pinchZoom(input.pinchZoom())
                .tapActions(tapMap(input)).longTapActions(longTapMap(input)).gestureActions(gestureMap(input))
                .semanticStyles(stylesToDomain(s.styleSheet()))
                .build();
    }

    private static ReaderStyleSheet styleSheetFromDomain(Map<String, ReaderElementStylePreferences> persisted) {
        if (persisted == null || persisted.isEmpty()) return ReaderStyleSheet.defaults();
        EnumMap<ReaderSemanticElement, ReaderElementStyle> overrides = new EnumMap<>(ReaderSemanticElement.class);
        persisted.forEach((key, value) -> {
            if (key == null || value == null) return;
            try {
                ReaderSemanticElement element = ReaderSemanticElement.valueOf(key);
                overrides.put(element, new ReaderElementStyle(
                        value.getFontFamily(), value.getFontSize(), value.getFontScale(), value.getFontWeight(),
                        value.getColor(), value.getAlignment(), value.getSpacingBefore(), value.getSpacingAfter()));
            } catch (IllegalArgumentException ignored) {
                // Forward/backward compatibility: unknown semantic style keys are ignored.
            }
        });
        return ReaderStyleSheet.withOverrides(overrides);
    }

    private static Map<String, ReaderElementStylePreferences> stylesToDomain(ReaderStyleSheet sheet) {
        ReaderStyleSheet effective = sheet != null ? sheet : ReaderStyleSheet.defaults();
        Map<String, ReaderElementStylePreferences> result = new LinkedHashMap<>();
        effective.styles().forEach((element, style) -> result.put(element.name(),
                ReaderElementStylePreferences.builder()
                        .fontFamily(style.fontFamily())
                        .fontSize(style.fontSize())
                        .fontScale(style.fontScale())
                        .fontWeight(style.fontWeight())
                        .color(style.color())
                        .alignment(style.alignment())
                        .spacingBefore(style.spacingBefore())
                        .spacingAfter(style.spacingAfter())
                        .build()));
        return Map.copyOf(result);
    }

    private static ReaderInputSettings inputFromDomain(ReaderPreferences p) {
        ReaderInputSettings legacy = ReaderInputSettings.fromLegacy(
                p.getTapLeftAction(), p.getTapCenterAction(), p.getTapRightAction());
        Map<String, String> taps = safe(p.getTapActions());
        Map<String, String> longs = safe(p.getLongTapActions());
        Map<String, String> gestures = safe(p.getGestureActions());
        return new ReaderInputSettings(
                get(taps,"top-left",legacy.topLeft()), get(taps,"top-center",legacy.topCenter()), get(taps,"top-right",legacy.topRight()),
                get(taps,"middle-left",legacy.middleLeft()), get(taps,"middle-center",legacy.middleCenter()), get(taps,"middle-right",legacy.middleRight()),
                get(taps,"bottom-left",legacy.bottomLeft()), get(taps,"bottom-center",legacy.bottomCenter()), get(taps,"bottom-right",legacy.bottomRight()),
                get(longs,"top-left",legacy.longTopLeft()), get(longs,"top-center",legacy.longTopCenter()), get(longs,"top-right",legacy.longTopRight()),
                get(longs,"middle-left",legacy.longMiddleLeft()), get(longs,"middle-center",legacy.longMiddleCenter()), get(longs,"middle-right",legacy.longMiddleRight()),
                get(longs,"bottom-left",legacy.longBottomLeft()), get(longs,"bottom-center",legacy.longBottomCenter()), get(longs,"bottom-right",legacy.longBottomRight()),
                get(gestures,"swipe-left",legacy.swipeLeft()), get(gestures,"swipe-right",legacy.swipeRight()),
                get(gestures,"swipe-up",legacy.swipeUp()), get(gestures,"swipe-down",legacy.swipeDown()), p.isPinchZoom());
    }

    private static Map<String,String> tapMap(ReaderInputSettings i) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("top-left",i.topLeft()); m.put("top-center",i.topCenter()); m.put("top-right",i.topRight());
        m.put("middle-left",i.middleLeft()); m.put("middle-center",i.middleCenter()); m.put("middle-right",i.middleRight());
        m.put("bottom-left",i.bottomLeft()); m.put("bottom-center",i.bottomCenter()); m.put("bottom-right",i.bottomRight());
        return Map.copyOf(m);
    }
    private static Map<String,String> longTapMap(ReaderInputSettings i) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("top-left",i.longTopLeft()); m.put("top-center",i.longTopCenter()); m.put("top-right",i.longTopRight());
        m.put("middle-left",i.longMiddleLeft()); m.put("middle-center",i.longMiddleCenter()); m.put("middle-right",i.longMiddleRight());
        m.put("bottom-left",i.longBottomLeft()); m.put("bottom-center",i.longBottomCenter()); m.put("bottom-right",i.longBottomRight());
        return Map.copyOf(m);
    }
    private static Map<String,String> gestureMap(ReaderInputSettings i) {
        return Map.of("swipe-left",i.swipeLeft(), "swipe-right",i.swipeRight(),
                "swipe-up",i.swipeUp(), "swipe-down",i.swipeDown());
    }
    private static Map<String,String> safe(Map<String,String> map) { return map == null ? Map.of() : map; }
    private static String get(Map<String,String> map, String key, String fallback) {
        String value = map.get(key); return value == null || value.isBlank() ? fallback : value;
    }
}
