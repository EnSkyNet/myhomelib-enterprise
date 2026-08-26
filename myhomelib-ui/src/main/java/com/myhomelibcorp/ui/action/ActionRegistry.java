package com.myhomelibcorp.ui.action;

import com.myhomelibcorp.application.action.ActionPreference;
import com.myhomelibcorp.application.action.ActionSettingsService;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Centralized desktop command registry with persistence, context predicates and accelerators. */
@Component
@RequiredArgsConstructor
public class ActionRegistry {
    private final ActionSettingsService settingsService;
    private final LinkedHashMap<String, RegisteredAction> actions = new LinkedHashMap<>();
    private final List<KeyCombination> installedSceneAccelerators = new ArrayList<>();
    private Scene scene;

    public synchronized void register(ActionDefinition definition, MenuItem menuItem,
                                      BooleanSupplier contextPredicate, Runnable handler) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        ActionPreference preference = settingsService.load(
                definition.id(), definition.defaultShortcut(), definition.defaultVisible());
        RegisteredAction action = new RegisteredAction(definition, menuItem,
                contextPredicate == null ? () -> true : contextPredicate, handler, preference);
        actions.put(definition.id(), action);
        if (menuItem != null) menuItem.setOnAction(e -> execute(definition.id()));
        rebuildAccelerators();
        refreshContexts();
    }

    public synchronized void attach(Scene scene) {
        if (this.scene == scene) return;
        removeInstalledAccelerators();
        this.scene = scene;
        rebuildAccelerators();
        refreshContexts();
    }

    public void execute(String commandId) {
        RegisteredAction action;
        synchronized (this) { action = actions.get(commandId); }
        if (action == null || !action.preference.visible() || !safeContext(action.contextPredicate)) return;
        action.handler.run();
    }

    public synchronized void refreshContexts() {
        for (RegisteredAction action : actions.values()) {
            if (action.menuItem != null) {
                action.menuItem.setVisible(action.preference.visible());
                action.menuItem.setDisable(!safeContext(action.contextPredicate));
            }
        }
    }

    public synchronized List<ActionSnapshot> snapshot() {
        return actions.values().stream().map(a -> new ActionSnapshot(
                a.definition.id(), a.definition.title(), a.definition.defaultShortcut(), a.definition.defaultVisible(),
                a.preference.shortcut(), a.preference.visible())).toList();
    }

    public synchronized void apply(Map<String, ActionPreference> preferences) {
        if (preferences == null) return;
        validate(preferences);
        for (var entry : preferences.entrySet()) {
            RegisteredAction action = actions.get(entry.getKey());
            if (action == null) continue;
            action.preference = entry.getValue();
            settingsService.save(entry.getKey(), entry.getValue());
        }
        rebuildAccelerators();
        refreshContexts();
    }

    public synchronized void resetDefaults() {
        for (RegisteredAction action : actions.values()) {
            settingsService.reset(action.definition.id());
            action.preference = new ActionPreference(action.definition.defaultShortcut(), action.definition.defaultVisible());
        }
        rebuildAccelerators();
        refreshContexts();
    }

    /** Returns all syntax/conflict errors without mutating the registry. */
    public synchronized List<String> validate(Map<String, ActionPreference> preferences) {
        List<String> errors = new ArrayList<>();
        Map<String, String> byAccelerator = new LinkedHashMap<>();
        for (RegisteredAction action : actions.values()) {
            ActionPreference pref = preferences.getOrDefault(action.definition.id(), action.preference);
            if (!pref.visible() || pref.shortcut().isBlank()) continue;
            try {
                KeyCombination combination = KeyCombination.valueOf(pref.shortcut());
                String normalized = combination.getName();
                String previous = byAccelerator.putIfAbsent(normalized, action.definition.id());
                if (previous != null && !previous.equals(action.definition.id())) {
                    errors.add("Конфлікт " + normalized + ": " + previous + " ↔ " + action.definition.id());
                }
            } catch (RuntimeException ex) {
                errors.add("Некоректна комбінація для " + action.definition.id() + ": " + pref.shortcut());
            }
        }
        return List.copyOf(errors);
    }

    private void rebuildAccelerators() {
        removeInstalledAccelerators();
        for (RegisteredAction action : actions.values()) {
            if (action.menuItem != null) action.menuItem.setAccelerator(null);
            if (!action.preference.visible() || action.preference.shortcut().isBlank()) continue;
            try {
                KeyCombination combination = KeyCombination.valueOf(action.preference.shortcut());
                if (action.menuItem != null) {
                    action.menuItem.setAccelerator(combination);
                } else if (scene != null) {
                    scene.getAccelerators().put(combination, () -> execute(action.definition.id()));
                    installedSceneAccelerators.add(combination);
                }
            } catch (RuntimeException ignored) {
                // Invalid persisted input is surfaced in customization dialog, never allowed to crash startup.
            }
        }
    }

    private void removeInstalledAccelerators() {
        if (scene != null) for (KeyCombination key : installedSceneAccelerators) scene.getAccelerators().remove(key);
        installedSceneAccelerators.clear();
    }

    private static boolean safeContext(BooleanSupplier predicate) {
        try { return predicate == null || predicate.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    public record ActionSnapshot(String id, String title, String defaultShortcut, boolean defaultVisible,
                                 String shortcut, boolean visible) { }

    private static final class RegisteredAction {
        private final ActionDefinition definition;
        private final MenuItem menuItem;
        private final BooleanSupplier contextPredicate;
        private final Runnable handler;
        private ActionPreference preference;

        private RegisteredAction(ActionDefinition definition, MenuItem menuItem, BooleanSupplier contextPredicate,
                                 Runnable handler, ActionPreference preference) {
            this.definition = definition;
            this.menuItem = menuItem;
            this.contextPredicate = contextPredicate;
            this.handler = handler;
            this.preference = preference;
        }
    }
}
