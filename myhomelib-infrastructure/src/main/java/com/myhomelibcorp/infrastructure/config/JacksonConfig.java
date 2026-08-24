package com.myhomelibcorp.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфігурація Jackson для Spring-контексту.
 *
 * Jackson використовується інфраструктурним шаром для імпорту/експорту
 * користувацьких даних. Сам факт наявності jackson-databind у classpath
 * не гарантує створення ObjectMapper у цьому складі залежностей, тому
 * mapper реєструється явно як singleton Spring bean.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}