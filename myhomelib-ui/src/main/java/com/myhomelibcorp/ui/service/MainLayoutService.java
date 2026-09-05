package com.myhomelibcorp.ui.service;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
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
        allowCenterToShrink(leftSidebar, rightSidebar);
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

    /**
     * BorderPane honours the minimum width of its center node. A workspace with
     * wide tables/controls can therefore keep the expanded center width after a
     * sidebar was hidden. Restoring the sidebar would then push the right edge
     * outside the Scene instead of shrinking the center back into the available
     * width. The main center is a viewport and must always be shrinkable.
     */
    private static void allowCenterToShrink(Node... sidebars) {
        if (sidebars == null) return;
        for (Node sidebar : sidebars) {
            if (sidebar == null) continue;
            Parent parent = sidebar.getParent();
            if (parent instanceof BorderPane borderPane) {
                Node center = borderPane.getCenter();
                if (center instanceof Region region) {
                    region.setMinWidth(0);
                }
                return;
            }
        }
    }

    private static void apply(Node node, boolean visible) {
        if (node == null) return;
        node.setVisible(visible);
        node.setManaged(visible);
        requestLayout(node);
        // Force the owning BorderPane to recompute the center/side geometry after
        // managed state changes. A request alone can leave the previous expanded
        // center allocation alive until a later pulse, which makes a restored
        // sidebar extend beyond the Scene bounds.
        javafx.application.Platform.runLater(() -> relayoutOwner(node));
    }

    private static void relayoutOwner(Node node) {
        Parent parent = node == null ? null : node.getParent();
        if (parent instanceof BorderPane borderPane) {
            borderPane.applyCss();
            borderPane.requestLayout();
            borderPane.layout();
        } else {
            requestLayout(node);
        }
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
