package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.service.LanguageResolver;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ClassicLibraryActionsService {
    private final BookQueryRepository query;
    private final BookCommandRepository commands;
    private final SearchIndexer indexer;
    private final BookMapper mapper;
    private final BookSaver bookSaver;
    private final ApplicationSettingsPort settings;
    private final DialogService dialogs;

    public ClassicLibraryActionsService(BookQueryRepository query, BookCommandRepository commands,
                                        SearchIndexer indexer, BookMapper mapper, BookSaver bookSaver,
                                        ApplicationSettingsPort settings, DialogService dialogs) {
        this.query=query; this.commands=commands; this.indexer=indexer; this.mapper=mapper; this.bookSaver=bookSaver;
        this.settings=settings; this.dialogs=dialogs;
    }

    public List<BookDto> newBooks(int limit) {
        return query.findRecentlyAdded(limit).stream().map(mapper::toDto).toList();
    }

    public boolean editBook(Window owner, BookId id) {
        Book book = query.findById(id).orElse(null);
        if (book == null) { dialogs.showWarning("Книгу не знайдено", "Запис уже відсутній у колекції."); return false; }
        Dialog<ButtonType> d = new Dialog<>(); d.setTitle("Редагування книги"); d.setHeaderText(book.getTitle());
        if(owner!=null)d.initOwner(owner); d.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        GridPane g=new GridPane(); g.setHgap(10);g.setVgap(7);g.setPadding(new Insets(12));
        TextField title=f(book.getTitle()), authors=f(book.authorsText()), series=f(book.getSeries()), seq=f(book.getSequenceNumber()==null?"":book.getSequenceNumber().toString());
        TextField lang=f(book.getLanguage()==null?"":book.getLanguage().toString()), year=f(book.getYear()==null?"":book.getYear().toString()), publisher=f(book.getPublisher()), keywords=f(book.getKeywords());
        TextArea annotation=ta(book.getAnnotation()), review=ta(book.getReview());
        int r=0; r=add(g,r,"Назва",title); r=add(g,r,"Автори (;)",authors); r=add(g,r,"Серія",series); r=add(g,r,"№ у серії",seq);
        r=add(g,r,"Мова",lang); r=add(g,r,"Рік",year); r=add(g,r,"Видавець",publisher); r=add(g,r,"Ключові слова",keywords);
        r=add(g,r,"Анотація",annotation); add(g,r,"Відгук",review); d.getDialogPane().setContent(g); d.setResizable(true);
        if(d.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return false;
        if(title.getText()==null||title.getText().isBlank()){ dialogs.showWarning("Некоректні дані","Назва не може бути порожньою."); return false; }
        try {
            BookMetadata old=book.getMetadata();
            BookMetadata md=BookMetadata.builder().annotation(annotation.getText()).keywords(keywords.getText())
                    .language(LanguageResolver.resolve(lang.getText())).isbn(old.getIsbn()).review(review.getText())
                    .year(intOrNull(year.getText())).publisher(publisher.getText()).rate(old.getRate()).progress(old.getProgress()).build();
            List<Author> authorList=parseAuthors(authors.getText(),book.getAuthors());
            Book updated=Book.builder().id(book.getId()).title(title.getText().trim()).authors(authorList).genres(book.getGenres())
                    .series(blankNull(series.getText())).sequenceNumber(intOrNull(seq.getText())).metadata(md).file(book.getFile()).cover(book.getCover())
                    .updateDate(java.time.LocalDateTime.now()).createdAt(book.getCreatedAt()).deleted(book.isDeleted()).local(book.isLocal())
                    .missingSince(book.getMissingSince()).build();
            commands.save(updated); indexer.indexBook(updated); indexer.commit(); return true;
        } catch(Exception e){ dialogs.showError("Помилка редагування",e.getMessage()); return false; }
    }

    public boolean deleteBook(BookId id) {
        Book book=query.findById(id).orElse(null); if(book==null)return false;
        if(settings.getBoolean("ui.confirmDelete",true) && !dialogs.showConfirmation("Видалити книгу?", book.getTitle(), "Запис буде видалено з каталогу. Файл на диску не видаляється.")) return false;
        bookSaver.deleteBook(id);
        return true;
    }

    private List<Author> parseAuthors(String value,List<Author> fallback){
        if(value==null||value.isBlank())return fallback;
        List<Author> result=new ArrayList<>();
        for(String part:value.split(";")){String p=part.trim();if(p.isBlank())continue; String[] w=p.split("\\s+");
            if(w.length==1) result.add(new Author("", "", w[0]));
            else if(w.length==2) result.add(new Author(w[1], "", w[0]));
            else result.add(new Author(w[1], String.join(" ",java.util.Arrays.copyOfRange(w,2,w.length)), w[0])); }
        return result.isEmpty()?fallback:result;
    }
    private static TextField f(String s){return new TextField(s==null?"":s);} private static TextArea ta(String s){TextArea a=new TextArea(s==null?"":s);a.setPrefRowCount(4);a.setWrapText(true);return a;}
    private static int add(GridPane g,int row,String label,javafx.scene.Node n){Label l=new Label(label);g.add(l,0,row);g.add(n,1,row);GridPane.setHgrow(n,Priority.ALWAYS);return row+1;}
    private static String blankNull(String s){return s==null||s.isBlank()?null:s.trim();} private static String blankDefault(String s,String d){return s==null||s.isBlank()?d:s.trim();}
    private static Integer intOrNull(String s){try{return s==null||s.isBlank()?null:Integer.valueOf(s.trim());}catch(Exception e){return null;}}
}
