package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
public class CopyBooksBetweenCollectionsUseCase {
    private final BookQueryRepository books;
    private final CollectionRepository collections;
    private final BookResourcePort resources;
    private final CollectionLifecycleService lifecycle;
    private final ImportFileUseCase importer;

    public record Result(int copied,int failed,List<String> errors){}

    public Result execute(List<BookId> ids, String targetCollectionId) {
        if(ids==null||ids.isEmpty()) return new Result(0,0,List.of());
        Collection source=lifecycle.getCurrentCollection(); if(source==null) throw new IllegalStateException("Активну колекцію не вибрано");
        Collection target=collections.findById(targetCollectionId).orElseThrow(() -> new IllegalArgumentException("Цільову колекцію не знайдено"));
        if(source.getId().equals(target.getId())) throw new IllegalArgumentException("Виберіть іншу колекцію");
        Path root=target.getRootFolder(); if(root==null) throw new IllegalStateException("Цільова колекція не має кореневої папки");
        Path staging=root.resolve(".myhomelib-import-"+UUID.randomUUID());
        List<Path> files=new ArrayList<>(); List<String> errors=new ArrayList<>(); int copied=0;
        try {
            Files.createDirectories(staging);
            for(BookId id:ids){
                try{Book b=books.findById(id).orElseThrow();String src=b.getArchiveEntry();if(src==null||src.isBlank())src=b.getFileName();
                    String ext=extension(src);String base=safe(b.authorsText()+" - "+b.getTitle());Path out=staging.resolve(base+"-"+b.getId().asString().substring(0,Math.min(8,b.getId().asString().length()))+ext);
                    try(InputStream in=resources.readBookData(b).orElseThrow(() -> new IllegalStateException("Файл недоступний"))){Files.copy(in,out,StandardCopyOption.REPLACE_EXISTING);} files.add(out);
                }catch(Exception e){errors.add(id+": "+e.getMessage());}
            }
            lifecycle.initializeCollection(target,false);
            for(Path f:files){try{importer.execute(ImportContext.builder().file(f).rootDirectory(root).batchSize(1000).indexAfterSave(false).build());copied++;}catch(Exception e){errors.add(f.getFileName()+": "+e.getMessage());}}
            lifecycle.rebuildSearchIndex();
        } catch(Exception e){ throw new IllegalStateException("Не вдалося скопіювати книги: "+e.getMessage(),e); }
        finally {
            try{lifecycle.initializeCollection(source,true);}catch(Exception e){log.error("Не вдалося повернутися до вихідної колекції",e);}
            try{if(Files.exists(staging))try(var walk=Files.walk(staging)){walk.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}catch(Exception ignored){}
        }
        return new Result(copied,errors.size(),List.copyOf(errors));
    }
    private static String safe(String s){if(s==null||s.isBlank())return"book";return s.replaceAll("[<>:\"/\\\\|?*]","_").replaceAll("\\s+"," ").trim();}
    private static String extension(String s){if(s==null)return".book";int i=s.lastIndexOf('.');return i<0?".book":s.substring(i).toLowerCase();}
}
