package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookCommandRepository;
import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteAuthorRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBookRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

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