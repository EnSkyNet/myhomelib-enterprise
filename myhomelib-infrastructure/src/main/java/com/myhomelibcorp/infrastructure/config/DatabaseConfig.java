package com.myhomelibcorp.infrastructure.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {


//    public BookQueryRepository sqliteBookQueryRepository(JdbcTemplate jdbcTemplate, AuthorRepository authorRepository) {
//        return new SqliteBookRepository(jdbcTemplate, authorRepository);
//    }
//
//    @Bean
//    @ConditionalOnProperty(name = "app.database.type", havingValue = "sqlite", matchIfMissing = true)
//    @Primary
//    public BookCommandRepository sqliteBookCommandRepository(JdbcTemplate jdbcTemplate, AuthorRepository authorRepository) {
//        return new SqliteBookRepository(jdbcTemplate, authorRepository);
//    }

//    @Bean
//    @ConditionalOnProperty(name = "app.database.type", havingValue = "sqlite", matchIfMissing = true)
//    @Primary
//    public AuthorRepository sqliteAuthorRepository(JdbcTemplate jdbcTemplate) {
//        return new SqliteAuthorRepository(jdbcTemplate);
//    }
}