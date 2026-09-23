package com.myhomelibcorp.ui.service;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;

/**
 * Localizes JavaFX's built-in dialog buttons without replacing their ButtonType identity.
 *
 * <p>JavaFX exposes {@link ButtonType#OK}, {@link ButtonType#CANCEL}, {@link ButtonType#YES},
 * {@link ButtonType#NO} and {@link ButtonType#CLOSE} with fixed English display text. Replacing
 * the button types themselves is unsafe because application code commonly compares dialog
 * results by identity. This helper changes only the rendered button node text and watches the
 * button-type list so types added after dialog construction are localized as well.</p>
 */
public final class DialogLocalization {
    private static final Object LISTENER_KEY = DialogLocalization.class.getName() + ".listener";

    private DialogLocalization() { }

    /** Prepare any JavaFX dialog/alert/choice/text-input dialog for localized standard buttons. */
    public static <D extends Dialog<?>> D prepare(D dialog) {
        if (dialog == null) return null;
        DialogPane pane = dialog.getDialogPane();
        if (pane != null && !pane.getProperties().containsKey(LISTENER_KEY)) {
            ListChangeListener<ButtonType> listener = change -> apply(pane);
            pane.getButtonTypes().addListener(listener);
            pane.getProperties().put(LISTENER_KEY, listener);
        }
        apply(pane);
        return dialog;
    }

    /** Re-apply button text, useful after a runtime language change. */
    public static void apply(DialogPane pane) {
        if (pane == null) return;
        for (ButtonType type : pane.getButtonTypes()) {
            String key = localizationKey(type);
            if (key == null) continue;
            Node node = pane.lookupButton(type);
            if (node instanceof Labeled labeled && !labeled.textProperty().isBound()) {
                labeled.setText(UiText.text(key));
            }
        }
    }

    private static String localizationKey(ButtonType type) {
        if (type == ButtonType.OK) return "common.ok";
        if (type == ButtonType.CANCEL) return "common.cancel";
        if (type == ButtonType.YES) return "common.yes";
        if (type == ButtonType.NO) return "common.no";
        if (type == ButtonType.CLOSE) return "common.close";
        return null;
    }
}
