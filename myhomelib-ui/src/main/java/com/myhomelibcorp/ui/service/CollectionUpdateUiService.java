package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionFromNetworkUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
public class CollectionUpdateUiService {
    private final UpdateCollectionFromNetworkUseCase useCase;
    private final ApplicationSettingsPort settings;
    private final ApplicationState state;
    private final DialogService dialogs;
    private final UiBackgroundExecutor executor;
    private volatile AtomicBoolean active;

    public void updateFromNetwork(Window owner,Runnable onDone){
        Collection c=state.getCurrentLibraryCollection();if(c==null){dialogs.showWarning("Колекція","Спочатку виберіть колекцію.");return;}
        String key="collection."+c.getId()+".inpxUrl";String current=settings.get(key,"");
        if(current.isBlank()&&c.getUrl()!=null&&c.getUrl().toLowerCase().contains(".inpx"))current=c.getUrl();
        Optional<String> v=dialogs.showTextInput("Оновлення колекції","URL актуального INPX","INPX URL:",current);if(v.isEmpty()||v.get().isBlank())return;
        settings.put(key,v.get().trim()); AtomicBoolean flag=new AtomicBoolean(false); active=flag;
        state.getStatusBar().setProgressVisible(true);state.getStatusBar().setStatusText("Завантаження INPX…");
        executor.submit(() -> useCase.execute(c,v.get().trim(),flag,p->Platform.runLater(()->state.getStatusBar().setProgress(p))))
                .whenComplete((r,e)->Platform.runLater(()->{if(active==flag)active=null;state.getStatusBar().setProgressVisible(false);if(e!=null)dialogs.showError("Оновлення колекції",unwrap(e).getMessage());else{state.getStatusBar().setStatusText("Колекцію оновлено");dialogs.showInfo("Оновлення","Імпортовано/оновлено: "+r.imported());if(onDone!=null)onDone.run();}}));
    }
    public boolean cancel(){AtomicBoolean f=active;if(f==null)return false;f.set(true);state.getStatusBar().setStatusText("Скасування оновлення…");return true;}
    private Throwable unwrap(Throwable e){while(e.getCause()!=null&&(e instanceof java.util.concurrent.CompletionException||e instanceof java.util.concurrent.ExecutionException))e=e.getCause();return e;}
}
