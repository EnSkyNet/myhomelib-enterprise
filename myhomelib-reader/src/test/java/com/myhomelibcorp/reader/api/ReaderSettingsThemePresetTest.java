package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderSettingsThemePresetTest {

    @Test
    void themePresetRemovesOnlyColorOverridesSoToolbarSwitchIsVisible() {
        ReaderSettings settings = withCustomCss("""
                body { letter-spacing: 0.02em; }
                --reader-background: #123456;
                --reader-foreground: #abcdef;
                .chapter { margin-top: 2em; }
                """);

        ReaderSettings dark = settings.withThemePreset("dark");

        assertThat(dark.themeName()).isEqualTo("dark");
        assertThat(dark.customCss())
                .contains("letter-spacing: 0.02em")
                .contains("margin-top: 2em")
                .doesNotContain("--reader-background")
                .doesNotContain("--reader-foreground");
        assertThat(ReaderTheme.fromSettings(dark).background()).isEqualTo(ReaderTheme.dark().background());
        assertThat(ReaderTheme.fromSettings(dark).foreground()).isEqualTo(ReaderTheme.dark().foreground());
    }

    @Test
    void themePresetSupportsAmoledAndPreservesUnrelatedCss() {
        ReaderSettings settings = withCustomCss(".reader { font-variant-ligatures: none; }");

        ReaderSettings amoled = settings.withThemePreset("amoled");

        assertThat(ReaderTheme.fromSettings(amoled).background()).isEqualTo("#000000");
        assertThat(amoled.customCss()).contains("font-variant-ligatures");
    }

    private ReaderSettings withCustomCss(String css) {
        ReaderSettings base = ReaderSettings.defaultSettings();
        return new ReaderSettings(
                base.themeName(), base.fontFamily(), base.fontSize(), base.lineSpacing(), base.paragraphSpacing(),
                base.firstLineIndent(), base.alignment(), base.leftMargin(), base.rightMargin(), base.topMargin(),
                base.bottomMargin(), base.hyphenation(), base.pageMode(), base.autoScroll(), base.scrollSpeed(),
                base.showToolbar(), css, base.showStatusBar(), base.showStatusProgress(), base.showStatusChapter(),
                base.showStatusPage(), base.tapLeftAction(), base.tapCenterAction(), base.tapRightAction(),
                base.twoPageMode(), base.autoTwoPageLandscape(), base.showStatusClock(), base.input(), base.styleSheet());
    }
}
