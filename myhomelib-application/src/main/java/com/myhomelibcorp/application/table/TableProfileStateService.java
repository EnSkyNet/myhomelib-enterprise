package com.myhomelibcorp.application.table;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** JavaFX-neutral persisted table-profile state. */
@Component
@RequiredArgsConstructor
public class TableProfileStateService {
    private static final String ROOT = "table.profile.";
    private final ApplicationSettingsPort settings;

    public TableProfileState load(String profile, List<TableColumnProfile> defaults) {
        String p = prefix(profile);
        List<TableColumnProfile> result = new ArrayList<>();
        List<TableColumnProfile> safeDefaults = defaults == null ? List.of() : defaults;
        for (TableColumnProfile def : safeDefaults) {
            double width = doubleValue(p + "column." + def.id() + ".width", def.width());
            boolean visible = Boolean.parseBoolean(settings.get(p + "column." + def.id() + ".visible", Boolean.toString(def.visible())));
            int order = intValue(p + "column." + def.id() + ".order", def.order());
            result.add(new TableColumnProfile(def.id(), Math.max(30, width), visible, order));
        }
        return new TableProfileState(result,
                settings.get(p + "sort.column", ""),
                settings.get(p + "sort.direction", ""));
    }

    public void save(String profile, TableProfileState state) {
        String p = prefix(profile);
        if (state == null) return;
        for (TableColumnProfile column : state.columns()) {
            settings.put(p + "column." + column.id() + ".width", Double.toString(column.width()));
            settings.putBoolean(p + "column." + column.id() + ".visible", column.visible());
            settings.putInt(p + "column." + column.id() + ".order", column.order());
        }
        if (state.sortColumn().isBlank()) {
            settings.remove(p + "sort.column");
            settings.remove(p + "sort.direction");
        } else {
            settings.put(p + "sort.column", state.sortColumn());
            settings.put(p + "sort.direction", state.sortDirection());
        }
    }

    public void reset(String profile) {
        String p = prefix(profile);
        for (String key : settings.findByPrefix(p).keySet()) settings.remove(key);
    }

    private String prefix(String profile) { return ROOT + safe(profile) + "."; }
    private static String safe(String value) { return value == null || value.isBlank() ? "default" : value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private int intValue(String key, int fallback) { try { return Integer.parseInt(settings.get(key, Integer.toString(fallback))); } catch (Exception e) { return fallback; } }
    private double doubleValue(String key, double fallback) { try { return Double.parseDouble(settings.get(key, Double.toString(fallback))); } catch (Exception e) { return fallback; } }
}
