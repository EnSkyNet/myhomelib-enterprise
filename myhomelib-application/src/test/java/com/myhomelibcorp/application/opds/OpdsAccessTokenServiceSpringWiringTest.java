package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpdsAccessTokenServiceSpringWiringTest {

    @Test
    void springUsesProductionConstructorWhenClockTestConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ApplicationSettingsPort.class, () -> mock(ApplicationSettingsPort.class));
            context.register(OpdsAccessTokenService.class);

            context.refresh();

            OpdsAccessTokenService service = context.getBean(OpdsAccessTokenService.class);
            assertThat(service).isNotNull();
        }
    }
}
