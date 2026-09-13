package com.myhomelibcorp.ui.accessibility;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AccessibilitySourceContractTest {
    private static final Pattern BUTTON = Pattern.compile("<Button\\b[^>]*?(?:/>|>)", Pattern.DOTALL);
    private static final Pattern TEXT = Pattern.compile("\\btext=\"([^\"]*)\"");
    private static final Pattern CSS_VARIABLE = Pattern.compile("(?m)^\\s*(-mhl-[a-z-]+)\\s*:\\s*(#[0-9a-fA-F]{6})\\s*;");

    @Test
    void allFxmlButtonsHaveKeyboardFocusAndIconButtonsHaveAccessibleNamesOrTooltips() throws Exception {
        Path root = Path.of("src/main/resources/view");
        List<Path> views;
        try (Stream<Path> stream = Files.walk(root)) {
            views = stream.filter(path -> path.toString().endsWith(".fxml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        assertThat(views).isNotEmpty();

        for (Path view : views) {
            String xml = Files.readString(view, StandardCharsets.UTF_8);
            assertThat(xml).as(view + " must not hide buttons from keyboard focus")
                    .doesNotContainPattern("(?s)<Button\\b[^>]*focusTraversable=\"false\"");
            assertIconButtons(xml, root.relativize(view).toString());
        }
    }

    @Test
    void baseThemeSemanticColorsMeetNormalTextContrastThreshold() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/css/app-theme-base.css"), StandardCharsets.UTF_8);
        Map<String, String> colors = new LinkedHashMap<>();
        Matcher matcher = CSS_VARIABLE.matcher(css);
        while (matcher.find()) colors.put(matcher.group(1), matcher.group(2));

        assertContrast(colors, "-mhl-text", "-mhl-background", 4.5);
        assertContrast(colors, "-mhl-muted-text", "-mhl-background", 4.5);
        assertContrast(colors, "-mhl-on-accent", "-mhl-accent", 4.5);
        assertContrast(colors, "-mhl-on-accent", "-mhl-success", 4.5);
        assertContrast(colors, "-mhl-on-accent", "-mhl-warning", 4.5);
        assertContrast(colors, "-mhl-on-accent", "-mhl-danger", 4.5);
        assertContrast(colors, "-mhl-warning-text", "-mhl-warning-bg", 4.5);
    }

    @Test
    void contrastHelperMatchesWcagReferenceRatios() {
        assertThat(UiAccessibilitySupport.contrastRatio("#000000", "#FFFFFF"))
                .isCloseTo(21.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(UiAccessibilitySupport.contrastRatio("#777777", "#FFFFFF"))
                .isGreaterThanOrEqualTo(4.47);
    }

    private static void assertContrast(Map<String, String> colors, String foreground, String background, double minimum) {
        assertThat(colors).containsKeys(foreground, background);
        double ratio = UiAccessibilitySupport.contrastRatio(colors.get(foreground), colors.get(background));
        assertThat(ratio).as(foreground + " on " + background).isGreaterThanOrEqualTo(minimum);
    }

    private static void assertIconButtons(String xml, String name) {
        Matcher matcher = BUTTON.matcher(xml);
        while (matcher.find()) {
            String tag = matcher.group();
            Matcher text = TEXT.matcher(tag);
            if (text.find() && containsLetterOrDigit(text.group(1))) continue;
            boolean named = hasNonBlankAttribute(tag, "accessibleText") || nearbyTooltip(xml, matcher.end());
            assertThat(named).as(name + " icon button lacks accessible name: " + tag).isTrue();
        }
    }

    private static boolean hasNonBlankAttribute(String tag, String attribute) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(attribute) + "=\"([^\"]+)\"").matcher(tag);
        return matcher.find() && !matcher.group(1).isBlank();
    }

    private static boolean nearbyTooltip(String xml, int end) {
        int limit = Math.min(xml.length(), end + 320);
        return xml.substring(end, limit).contains("<Tooltip text=\"");
    }

    private static boolean containsLetterOrDigit(String value) {
        return value != null && value.codePoints().anyMatch(Character::isLetterOrDigit);
    }
}
