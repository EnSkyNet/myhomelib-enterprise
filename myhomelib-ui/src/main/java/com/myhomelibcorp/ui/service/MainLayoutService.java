package com.myhomelibcorp.ui.service;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import org.springframework.stereotype.Component;

/**
 * Single owner of the visibility state for the main left/right sidebars.
 * The nodes are registered by MainController after FXML construction, while
 * Reader and menu commands only manipulate this state and never reach into FXML.
 */
@Component
public class MainLayoutService {

    private final BooleanProperty leftSidebarVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty rightSidebarVisible = new SimpleBooleanProperty(true);

    private Node leftSidebar;
    private Node rightSidebar;

    public MainLayoutService() {
        // Properties are also changed through bidirectional menu bindings, so
        // node visibility must follow the property itself, not only setters.
        leftSidebarVisible.addListener((obs, oldValue, visible) -> apply(leftSidebar, visible));
        rightSidebarVisible.addListener((obs, oldValue, visible) -> apply(rightSidebar, visible));
    }

    public void registerSidebars(Node leftSidebar, Node rightSidebar) {
        this.leftSidebar = leftSidebar;
        this.rightSidebar = rightSidebar;
        apply(leftSidebar, leftSidebarVisible.get());
        apply(rightSidebar, rightSidebarVisible.get());
    }

    public BooleanProperty leftSidebarVisibleProperty() {
        return leftSidebarVisible;
    }

    public BooleanProperty rightSidebarVisibleProperty() {
        return rightSidebarVisible;
    }

    public boolean isLeftSidebarVisible() {
        return leftSidebarVisible.get();
    }

    public boolean isRightSidebarVisible() {
        return rightSidebarVisible.get();
    }

    public void setLeftSidebarVisible(boolean visible) {
        leftSidebarVisible.set(visible);
    }

    public void setRightSidebarVisible(boolean visible) {
        rightSidebarVisible.set(visible);
    }

    public void toggleLeftSidebar() {
        setLeftSidebarVisible(!isLeftSidebarVisible());
    }

    public void toggleRightSidebar() {
        setRightSidebarVisible(!isRightSidebarVisible());
    }

    private static void apply(Node node, boolean visible) {
        if (node == null) return;
        node.setVisible(visible);
        node.setManaged(visible);
        requestLayout(node);
        // BorderPane recalculates left/right geometry on the next pulse. This is
        // important when a sidebar is restored while Reader remains the current workspace.
        javafx.application.Platform.runLater(() -> requestLayout(node));
    }

    private static void requestLayout(Node node) {
        if (node instanceof Parent parent) {
            parent.requestLayout();
        }
        Parent parent = node.getParent();
        if (parent != null) {
            parent.requestLayout();
        }
    }
}
