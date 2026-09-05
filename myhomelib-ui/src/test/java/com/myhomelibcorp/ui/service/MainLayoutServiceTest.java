package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainLayoutServiceTest {

    @Test
    void rightSidebarToggleIsReversible() {
        MainLayoutService service = new MainLayoutService();

        assertThat(service.isRightSidebarVisible()).isTrue();
        service.toggleRightSidebar();
        assertThat(service.isRightSidebarVisible()).isFalse();
        service.toggleRightSidebar();
        assertThat(service.isRightSidebarVisible()).isTrue();
    }

    @Test
    void leftSidebarToggleIsReversible() {
        MainLayoutService service = new MainLayoutService();

        assertThat(service.isLeftSidebarVisible()).isTrue();
        service.toggleLeftSidebar();
        assertThat(service.isLeftSidebarVisible()).isFalse();
        service.toggleLeftSidebar();
        assertThat(service.isLeftSidebarVisible()).isTrue();
    }
}
