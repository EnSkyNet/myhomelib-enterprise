package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.application.port.out.resource.ReaderBookResourcePort;
import com.myhomelibcorp.infrastructure.adapter.ReaderBookResourceAdapter;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import com.myhomelibcorp.infrastructure.resource.BookResourceResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Конфігурація для Reader ресурсів.
 * Створює бін ReaderBookResourcePort для використання в Reader-сервісах.
 */
@Configuration
public class ReaderResourceConfig {

    @Bean
    @Primary
    public ReaderBookResourcePort readerBookResourcePort(
            BookResourceResolver bookResourceResolver,
            ZipArchiveReader zipArchiveReader
    ) {
        return new ReaderBookResourceAdapter(bookResourceResolver, zipArchiveReader);
    }
}