package com.myhomelibcorp.ui.table;

import com.myhomelibcorp.application.table.TableColumnProfile;
import com.myhomelibcorp.application.table.TableProfileState;
import com.myhomelibcorp.application.table.TableProfileStateService;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Stage 9 JavaFX adapter over the application-level persisted table-profile state. */
@Component
@RequiredArgsConstructor
public class TableProfileService {
    private final TableProfileStateService stateService;

    public <S> void apply(String profile, TableView<S> table, LinkedHashMap<String, TableColumn<S, ?>> columns) {
        List<TableColumnProfile> defaults = new ArrayList<>();
        int order = 0;
        for (var entry : columns.entrySet()) {
            TableColumn<S, ?> column = entry.getValue();
            double width = column.getPrefWidth() > 0 ? column.getPrefWidth() : Math.max(30, column.getWidth());
            defaults.add(new TableColumnProfile(entry.getKey(), width, column.isVisible(), order++));
        }

        TableProfileState state = stateService.load(profile, defaults);
        Map<String, TableColumnProfile> byId = state.columns().stream()
                .collect(Collectors.toMap(TableColumnProfile::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        for (var entry : columns.entrySet()) {
            TableColumnProfile saved = byId.get(entry.getKey());
            if (saved == null) continue;
            entry.getValue().setPrefWidth(Math.max(30, saved.width()));
            entry.getValue().setVisible(saved.visible());
        }

        List<Map.Entry<String, TableColumn<S, ?>>> ordered = new ArrayList<>(columns.entrySet());
        Map<String, Integer> defaultOrder = new LinkedHashMap<>();
        int i = 0;
        for (String id : columns.keySet()) defaultOrder.put(id, i++);
        ordered.sort(Comparator.comparingInt(entry -> {
            TableColumnProfile saved = byId.get(entry.getKey());
            return saved == null ? defaultOrder.get(entry.getKey()) : saved.order();
        }));

        List<TableColumn<S, ?>> finalColumns = new ArrayList<>();
        table.getColumns().stream().filter(c -> "select".equals(c.getId())).findFirst().ifPresent(finalColumns::add);
        ordered.forEach(entry -> finalColumns.add(entry.getValue()));
        table.getColumns().setAll(finalColumns);

        table.getSortOrder().clear();
        TableColumn<S, ?> sortColumn = columns.get(state.sortColumn());
        if (sortColumn != null && sortColumn.isSortable()) {
            try {
                sortColumn.setSortType(TableColumn.SortType.valueOf(state.sortDirection()));
            } catch (Exception ignored) {
                sortColumn.setSortType(TableColumn.SortType.ASCENDING);
            }
            table.getSortOrder().add(sortColumn);
        }
    }

    public <S> void save(String profile, TableView<S> table, LinkedHashMap<String, TableColumn<S, ?>> columns) {
        List<TableColumnProfile> savedColumns = new ArrayList<>();
        int order = 0;
        for (TableColumn<S, ?> column : table.getColumns()) {
            if ("select".equals(column.getId())) continue;
            String id = idOf(columns, column);
            if (id == null) continue;
            savedColumns.add(new TableColumnProfile(id, Math.max(30, column.getWidth()), column.isVisible(), order++));
        }

        String sortId = "";
        String sortDirection = "";
        if (!table.getSortOrder().isEmpty()) {
            TableColumn<S, ?> sortColumn = table.getSortOrder().getFirst();
            String id = idOf(columns, sortColumn);
            if (id != null) {
                sortId = id;
                sortDirection = sortColumn.getSortType().name();
            }
        }
        stateService.save(profile, new TableProfileState(savedColumns, sortId, sortDirection));
    }

    public void reset(String profile) {
        stateService.reset(profile);
    }

    private <S> String idOf(Map<String, TableColumn<S, ?>> columns, TableColumn<S, ?> target) {
        return columns.entrySet().stream()
                .filter(entry -> entry.getValue() == target)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
