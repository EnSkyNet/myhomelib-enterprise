package com.myhomelibcorp.ui.accessibility;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.List;

/** Small runtime accessibility pass for FXML/programmatic controls plus audit helpers used by regression tests. */
public final class UiAccessibilitySupport {
    private UiAccessibilitySupport() { }

    public static void enhance(Node root) {
        if (root == null) return;
        applyAccessibleName(root);
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) enhance(child);
        }
    }

    public static List<AccessibilityIssue> audit(Node root) {
        List<AccessibilityIssue> issues = new ArrayList<>();
        audit(root, issues);
        return List.copyOf(issues);
    }

    private static void audit(Node node, List<AccessibilityIssue> issues) {
        if (node == null) return;
        if (node instanceof Control control && isInteractive(control)) {
            if (accessibleName(control).isBlank()) {
                issues.add(new AccessibilityIssue(idOf(control), "missing-accessible-name"));
            }
            if (!control.isFocusTraversable() && control instanceof ButtonBase) {
                issues.add(new AccessibilityIssue(idOf(control), "button-not-keyboard-focusable"));
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) audit(child, issues);
        }
    }

    private static void applyAccessibleName(Node node) {
        if (!(node instanceof Control control) || !accessibleName(control).isBlank()) return;
        String name = "";
        if (control instanceof ButtonBase button && button.getTooltip() != null) {
            name = clean(button.getTooltip().getText());
        }
        if (name.isBlank() && control instanceof Labeled labeled && isMeaningfulText(labeled.getText())) name = clean(labeled.getText());
        if (name.isBlank() && control instanceof TextInputControl input) name = clean(input.getPromptText());
        if (name.isBlank()) name = humanizeId(control.getId());
        if (!name.isBlank()) control.setAccessibleText(name);
    }

    private static boolean isInteractive(Control control) {
        return control.isVisible() && !control.isDisabled();
    }

    private static String accessibleName(Control control) {
        String explicit = clean(control.getAccessibleText());
        if (!explicit.isBlank()) return explicit;
        if (control instanceof ButtonBase button) {
            Tooltip tooltip = button.getTooltip();
            if (tooltip != null && !clean(tooltip.getText()).isBlank()) return clean(tooltip.getText());
        }
        if (control instanceof Labeled labeled && isMeaningfulText(labeled.getText())) return clean(labeled.getText());
        if (control instanceof TextInputControl input && !clean(input.getPromptText()).isBlank()) return clean(input.getPromptText());
        return "";
    }

    private static String idOf(Control control) {
        String id = clean(control.getId());
        return id.isBlank() ? control.getClass().getSimpleName() : id;
    }

    private static String humanizeId(String id) {
        String value = clean(id);
        if (value.isBlank()) return "";
        return value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean isMeaningfulText(String value) {
        String cleaned = clean(value);
        return !cleaned.isBlank() && cleaned.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public static double contrastRatio(String first, String second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String value) {
        String hex = clean(value).replace("#", "");
        if (!hex.matches("(?i)[0-9a-f]{6}")) throw new IllegalArgumentException("Expected #RRGGBB color");
        double r = channel(hex.substring(0, 2));
        double g = channel(hex.substring(2, 4));
        double b = channel(hex.substring(4, 6));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(String value) {
        double c = Integer.parseInt(value, 16) / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    public record AccessibilityIssue(String control, String problem) { }
}
