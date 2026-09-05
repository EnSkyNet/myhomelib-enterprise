package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

@Service
public class BookListExportService {
    private final ApplicationState state; private final DialogService dialogs;
    public BookListExportService(ApplicationState state,DialogService dialogs){this.state=state;this.dialogs=dialogs;}
    public void export(Window owner,String format){
        List<BookViewModel> books=state.getBookTable().getBooks(); if(books.isEmpty()){dialogs.showWarning("Порожній список","Немає книг для експорту.");return;}
        String f=format.toLowerCase(Locale.ROOT);FileChooser fc=new FileChooser();fc.setTitle("Експорт поточного списку");fc.setInitialFileName("books."+f);fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(f.toUpperCase(Locale.ROOT),"*."+f));File file=fc.showSaveDialog(owner);if(file==null)return;
        try{String data=switch(f){case "html"->html(books);case "rtf"->rtf(books);default->txt(books);};Files.writeString(file.toPath(),data,StandardCharsets.UTF_8);dialogs.showInfo("Експорт завершено",books.size()+" книг -> "+file);}catch(Exception e){dialogs.showError("Помилка експорту",e.getMessage());}
    }
    private String txt(List<BookViewModel>b){StringBuilder s=new StringBuilder();int i=1;for(var x:b)s.append(i++).append(". ").append(x.getAuthorsText()).append(" — ").append(x.getTitle()).append(x.getSeries()==null||x.getSeries().isBlank()?"":" ["+x.getSeries()+"]").append('\n');return s.toString();}
    private String html(List<BookViewModel>b){StringBuilder s=new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>MyHomeLib</title><h1>MyHomeLib</h1><table border=\"1\"><tr><th>#</th><th>Автор</th><th>Назва</th><th>Серія</th><th>Жанри</th><th>Рейтинг</th></tr>");int i=1;for(var x:b)s.append("<tr><td>").append(i++).append("</td><td>").append(esc(x.getAuthorsText())).append("</td><td>").append(esc(x.getTitle())).append("</td><td>").append(esc(x.getSeries())).append("</td><td>").append(esc(x.getGenresText())).append("</td><td>").append(x.getRate()).append("</td></tr>");return s.append("</table>").toString();}
    private String rtf(List<BookViewModel>b){StringBuilder s=new StringBuilder("{\\rtf1\\ansi\\deff0\\uc1 MyHomeLib\\par ");int i=1;for(var x:b)s.append(i++).append(". ").append(rtfEsc(x.getAuthorsText())).append(" - ").append(rtfEsc(x.getTitle())).append("\\par ");return s.append('}').toString();}
    private String esc(String x){if(x==null)return"";return x.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private String rtfEsc(String x){if(x==null)return"";StringBuilder b=new StringBuilder();for(char c:x.toCharArray()){if(c=='\\'||c=='{'||c=='}')b.append('\\').append(c);else if(c>127)b.append("\\u").append((int)c).append('?');else b.append(c);}return b.toString();}
}
