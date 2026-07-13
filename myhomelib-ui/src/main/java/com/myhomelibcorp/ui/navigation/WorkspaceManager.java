package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.layout.BorderPane;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceManager {

    private final ApplicationState appState;
    private BorderPane mainPane;

    private final Deque<String> history = new ArrayDeque<>();
    private final Deque<String> forwardStack = new ArrayDeque<>();
    private String currentWorkspace = "dashboard";
    private String currentId = "";

    public void setMainPane(BorderPane mainPane) {
        this.mainPane = mainPane;
    }

    public void push(String workspace, String id) {
        if (currentWorkspace != null && !currentWorkspace.equals(workspace)) {
            history.push(currentWorkspace + ":" + currentId);
            forwardStack.clear();
        }
        currentWorkspace = workspace;
        currentId = id != null ? id : "";
        log.info("Перехід до воркспейсу: {} (id: {})", workspace, currentId);
    }

    public void goBack() {
        if (history.isEmpty()) return;
        forwardStack.push(currentWorkspace + ":" + currentId);
        String previous = history.pop();
        String[] parts = previous.split(":", 2);
        String workspace = parts[0];
        String id = parts.length > 1 ? parts[1] : "";
        log.info("Назад до: {} (id: {})", workspace, id);
        // Відновлення має виконуватися через MainController
        // Тут тільки зберігаємо стан
        currentWorkspace = workspace;
        currentId = id;
    }

    public void goForward() {
        if (forwardStack.isEmpty()) return;
        history.push(currentWorkspace + ":" + currentId);
        String next = forwardStack.pop();
        String[] parts = next.split(":", 2);
        String workspace = parts[0];
        String id = parts.length > 1 ? parts[1] : "";
        log.info("Вперед до: {} (id: {})", workspace, id);
        currentWorkspace = workspace;
        currentId = id;
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    public String getCurrentWorkspace() {
        return currentWorkspace;
    }

    public String getCurrentId() {
        return currentId;
    }
}