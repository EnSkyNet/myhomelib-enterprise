package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import org.springframework.stereotype.Service;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for checkbox/batch selection across book workspaces.
 * Row selection is deliberately not stored here: it represents the current book only.
 */
@Service
public class BookSelectionService {
    private final Set<BookId> selectedIds = new LinkedHashSet<>();
    private final Map<BookId, List<WeakReference<BookViewModel>>> boundRows = new LinkedHashMap<>();
    private final ReadOnlyIntegerWrapper selectedCount = new ReadOnlyIntegerWrapper(0);
    private boolean propagating;

    public BookSelectionService(ApplicationState appState) {
        appState.currentLibraryCollectionProperty().addListener((obs, previous, current) -> {
            String previousId = previous == null || previous.getId() == null ? null : previous.getId();
            String currentId = current == null || current.getId() == null ? null : current.getId();
            if (!java.util.Objects.equals(previousId, currentId)) clear();
        });
    }

    public synchronized void bind(BookViewModel row) {
        if (row == null || row.isGroupHeader() || row.getId() == null || row.getId().isBlank()) return;
        BookId id;
        try {
            id = BookId.fromString(row.getId());
        } catch (RuntimeException invalidId) {
            return;
        }

        prune(id);
        boundRows.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new WeakReference<>(row));
        row.setSelected(selectedIds.contains(id));
        row.selectedProperty().addListener((obs, oldValue, newValue) -> {
            if (propagating) return;
            setSelected(id, Boolean.TRUE.equals(newValue));
        });
    }

    public synchronized boolean isSelected(BookId id) {
        return id != null && selectedIds.contains(id);
    }

    public synchronized void setSelected(BookId id, boolean selected) {
        if (id == null) return;
        boolean changed = selected ? selectedIds.add(id) : selectedIds.remove(id);
        if (changed) selectedCount.set(selectedIds.size());
        propagate(id, selected);
    }

    public synchronized void toggle(BookId id) {
        if (id != null) setSelected(id, !selectedIds.contains(id));
    }

    public synchronized void clear() {
        if (selectedIds.isEmpty()) return;
        List<BookId> ids = List.copyOf(selectedIds);
        selectedIds.clear();
        selectedCount.set(0);
        ids.forEach(id -> propagate(id, false));
    }

    /**
     * Select/clear only the concrete rows supplied by the current visible workspace.
     * The count property is published once for the whole batch, which keeps Select All O(n)
     * instead of triggering an O(n²) master-state recalculation in the UI.
     */
    public synchronized void setSelected(Collection<BookViewModel> rows, boolean selected) {
        if (rows == null || rows.isEmpty()) return;
        int before = selectedIds.size();
        for (BookViewModel row : rows) {
            if (row == null || row.isGroupHeader() || row.getId() == null || row.getId().isBlank()) continue;
            try {
                BookId id = BookId.fromString(row.getId());
                if (selected) selectedIds.add(id);
                else selectedIds.remove(id);
                propagate(id, selected);
            } catch (RuntimeException ignored) {
                // Invalid transient/group rows are intentionally ignored.
            }
        }
        if (before != selectedIds.size()) selectedCount.set(selectedIds.size());
    }

    /** Bulk variant for DTO-based workspaces that share the same canonical BookId selection. */
    public synchronized void setSelectedIds(Collection<BookId> ids, boolean selected) {
        if (ids == null || ids.isEmpty()) return;
        int before = selectedIds.size();
        for (BookId id : ids) {
            if (id == null) continue;
            if (selected) selectedIds.add(id); else selectedIds.remove(id);
            propagate(id, selected);
        }
        if (before != selectedIds.size()) selectedCount.set(selectedIds.size());
    }

    public synchronized SelectionState stateIds(Collection<BookId> ids) {
        if (ids == null || ids.isEmpty()) return SelectionState.NONE;
        int total = 0;
        int checked = 0;
        for (BookId id : ids) {
            if (id == null) continue;
            total++;
            if (selectedIds.contains(id)) checked++;
        }
        if (total == 0 || checked == 0) return SelectionState.NONE;
        return checked == total ? SelectionState.ALL : SelectionState.PARTIAL;
    }

    public synchronized List<BookId> snapshot() {
        return List.copyOf(selectedIds);
    }

    public int count() {
        return selectedCount.get();
    }

    public ReadOnlyIntegerProperty selectedCountProperty() {
        return selectedCount.getReadOnlyProperty();
    }

    public synchronized SelectionState state(Collection<BookViewModel> rows) {
        if (rows == null || rows.isEmpty()) return SelectionState.NONE;
        int total = 0;
        int checked = 0;
        for (BookViewModel row : rows) {
            if (row == null || row.isGroupHeader() || row.getId() == null || row.getId().isBlank()) continue;
            total++;
            try {
                if (selectedIds.contains(BookId.fromString(row.getId()))) checked++;
            } catch (RuntimeException ignored) {
                total--;
            }
        }
        if (total == 0 || checked == 0) return SelectionState.NONE;
        return checked == total ? SelectionState.ALL : SelectionState.PARTIAL;
    }

    private void propagate(BookId id, boolean selected) {
        List<WeakReference<BookViewModel>> refs = boundRows.get(id);
        if (refs == null) return;
        propagating = true;
        try {
            refs.removeIf(ref -> {
                BookViewModel row = ref.get();
                if (row == null) return true;
                if (row.isSelected() != selected) row.setSelected(selected);
                return false;
            });
            if (refs.isEmpty()) boundRows.remove(id);
        } finally {
            propagating = false;
        }
    }

    private void prune(BookId id) {
        List<WeakReference<BookViewModel>> refs = boundRows.get(id);
        if (refs == null) return;
        refs.removeIf(ref -> ref.get() == null);
        if (refs.isEmpty()) boundRows.remove(id);
    }

    public enum SelectionState { NONE, PARTIAL, ALL }
}
