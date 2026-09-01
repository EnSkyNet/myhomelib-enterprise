package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.usecase.collection.CopyBooksBetweenCollectionsUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CollectionCopyUiService {
    private final CollectionRepository collections; private final CopyBooksBetweenCollectionsUseCase copy; private final ApplicationState state; private final DialogService dialogs; private final BookSelectionService selection;
    public CollectionCopyUiService(CollectionRepository collections,CopyBooksBetweenCollectionsUseCase copy,ApplicationState state,DialogService dialogs,BookSelectionService selection){this.collections=collections;this.copy=copy;this.state=state;this.dialogs=dialogs;this.selection=selection;}
    public void copySelected(Window owner,Runnable onComplete){
        List<BookId> ids=selection.snapshot();
        if(ids.isEmpty()){dialogs.showWarning("Немає книг","Відмітьте книги checkbox для копіювання.");return;}
        Collection current=state.getCurrentLibraryCollection();List<Collection> targets=collections.findAll().stream().filter(c->current==null||!c.getId().equals(current.getId())).toList();
        if(targets.isEmpty()){dialogs.showWarning("Немає цільової колекції","Створіть ще одну колекцію.");return;}
        ChoiceDialog<Collection> d=new ChoiceDialog<>(targets.get(0),targets);d.setTitle("Копіювати між колекціями");d.setHeaderText("Цільова колекція");d.setContentText("Колекція:");if(owner!=null)d.initOwner(owner);
        Collection target=d.showAndWait().orElse(null);if(target==null)return;
        try{var r=copy.execute(ids,target.getId());String msg="Скопійовано: "+r.copied()+"; помилок: "+r.failed();if(!r.errors().isEmpty())msg+="\n\n"+String.join("\n",r.errors().stream().limit(10).toList());dialogs.showInfo("Копіювання завершено",msg);if(onComplete!=null)onComplete.run();}catch(Exception e){dialogs.showError("Помилка копіювання",e.getMessage());}
    }
}
