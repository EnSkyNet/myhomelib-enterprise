package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrityCheckActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }
    @Test void onSafeRepairInfoShowsTheDocumentedMaintenanceExplanation() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(IntegrityCheckController.class);
        UiActionTestSupport.invoke(f.instance(), "onSafeRepairInfo");
        verify(f.dependency(DialogService.class)).showInfo(anyString(), anyString());
    }
}
