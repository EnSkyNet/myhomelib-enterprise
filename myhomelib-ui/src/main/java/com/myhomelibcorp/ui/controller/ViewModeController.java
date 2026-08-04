package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.table.TreeBookTableController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ViewModeController {

    private final ApplicationContext springContext;

    private boolean treeMode = false;
    private Node currentCenter;
    private BorderPane mainPane;

    public void init(BorderPane mainPane) {
        this.mainPane = mainPane;
        this.currentCenter = mainPane.getCenter();
    }

    public void toggleView() {
        treeMode = !treeMode;
        if (treeMode) {
            showTreeView();
        } else {
            showTableView();
        }
    }

    public void showTreeView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/tree-book-table.fxml"));
            loader.setControllerFactory(springContext::getBean);
            Node treeView = loader.load();
            TreeBookTableController controller = loader.getController();
            controller.loadBooks();
            currentCenter = mainPane.getCenter();
            mainPane.setCenter(treeView);
            log.info("Переключено на режим дерева");
        } catch (Exception e) {
            log.error("Помилка завантаження tree-view", e);
        }
    }

    public void showTableView() {
        if (currentCenter != null) {
            mainPane.setCenter(currentCenter);
            log.info("Переключено на режим таблиці");
        }
    }

    public boolean isTreeMode() {
        return treeMode;
    }

    public Node getCurrentView() {
        return mainPane.getCenter();
    }
}