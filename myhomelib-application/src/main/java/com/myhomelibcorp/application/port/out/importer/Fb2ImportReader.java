package com.myhomelibcorp.infrastructure.importer.reader;

import com.myhomelibcorp.application.port.out.importer.ImportReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Component
@Slf4j
public class Fb2ImportReader implements ImportReader {

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".fb2") || name.endsWith(".fbd");
    }

    @Override
    public Stream<Object[]> read(Path file) {
        // повертає потік масивів полів
        return Stream.empty(); // тимчасово
    }

    @Override
    public String getFormatName() {
        return "FB2";
    }
}