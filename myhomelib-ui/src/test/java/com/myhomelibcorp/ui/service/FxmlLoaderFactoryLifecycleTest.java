package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FxmlLoaderFactoryLifecycleTest {

    @Test
    void createsFreshAutowiredControllerForEveryReload() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        Dependency dependency = new Dependency();
        beanFactory.registerSingleton("dependency", dependency);
        AutowiredAnnotationBeanPostProcessor autowired = new AutowiredAnnotationBeanPostProcessor();
        autowired.setBeanFactory(beanFactory);
        beanFactory.addBeanPostProcessor(autowired);

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        FxmlLoaderFactory factory = new FxmlLoaderFactory(context);

        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 100; i++) {
            ReloadableController controller = (ReloadableController) factory.createController(ReloadableController.class);
            assertNotNull(controller.dependency);
            identities.add(controller);
        }

        assertEquals(100, identities.size(), "every FXML reload must receive a fresh controller instance");
    }

    static final class Dependency { }

    static final class ReloadableController {
        @Autowired
        Dependency dependency;
    }
}
