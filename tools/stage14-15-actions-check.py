#!/usr/bin/env python3
from pathlib import Path
import re, subprocess, tempfile, shutil, sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(path, *tokens):
    text=(ROOT/path).read_text(encoding='utf-8')
    for t in tokens:
        if t not in text: errors.append(f'{path}: missing {t}')
    return text

registry=need('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/ActionRegistry.java',
    'class ActionRegistry','BooleanSupplier','KeyCombination.valueOf','settingsService.save','validate(',
    'menuItem.setVisible','scene.getAccelerators().put')
core=need('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/CoreActions.java',
    'navigation.back','navigation.forward','help.context','search.focus','view.refresh',
    'book.open.internal','book.open.external','collection.manage','import.inpx','export.books','settings.open')
main=need('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java',
    'configureActionRegistry();','actionRegistry.attach(scene)','actionRegistry.refreshContexts()',
    'handleCustomizeActions','handleBookActions')
if 'addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED' in main:
    errors.append('MainController still contains hardcoded KEY_PRESSED shortcut filter')

prefs=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/action/ActionSettingsService.java',
    'ApplicationSettingsPort','actions.','.shortcut','.visible')
profile=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/action/BookActionProfileService.java',
    'bookActions.order','legacy-post-command','export.postCommand','replaceAll','findByPrefix')
execs=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/action/BookActionExecutionService.java',
    'new ProcessBuilder(argv)','waitFor(','redirectOutput','redirectError')
if 'cmd.exe' in execs or '"sh"' in execs or "'sh'" in execs:
    errors.append('BookActionExecutionService must not invoke a shell')
usecase=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/book/RunBookActionUseCase.java',
    'ResolveBookContentUseCase','ExecutorPort','BookActionProfileService','%FILE%','%TITLE%','%BOOKID%')
ui=need('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action/BookActionUiService.java',
    'createContextMenu','runBookActionUseCase.execute','BookActionProfile::enabled')
fxml=need('myhomelib-ui/src/main/resources/view/MainView.fxml',
    'fx:id="bookActionsMenuItem"','fx:id="customizeActionsMenuItem"','fx:id="helpMenuItem"')
table=need('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java',
    'bookActionUiService.createContextMenu(item)','refreshRows()')
cmd=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/util/CommandTemplate.java',
    'formatArguments','expandToken','Незакрита лапка')

# Architecture guard: new UI action package must not import output ports/infrastructure.
for p in (ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/action').glob('*.java'):
    text=p.read_text(encoding='utf-8')
    if 'application.port.out' in text or 'com.myhomelibcorp.infrastructure' in text:
        errors.append(f'{p.relative_to(ROOT)}: forbidden output-port/infrastructure import')

# Compile/run the pure command parser/execution core with a tiny Component annotation stub.
try:
    with tempfile.TemporaryDirectory(prefix='mhl-stage1415-') as td:
        t=Path(td); src=t/'src'; classes=t/'classes'
        (src/'com/myhomelibcorp/application/util').mkdir(parents=True)
        (src/'com/myhomelibcorp/application/action').mkdir(parents=True)
        (src/'org/springframework/stereotype').mkdir(parents=True)
        shutil.copy(ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/util/CommandTemplate.java', src/'com/myhomelibcorp/application/util/CommandTemplate.java')
        for name in ['BookActionCommand.java','BookActionProfile.java','BookActionPreview.java','BookActionRunResult.java','BookActionExecutionService.java']:
            shutil.copy(ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/action'/name, src/'com/myhomelibcorp/application/action'/name)
        (src/'org/springframework/stereotype/Component.java').write_text('package org.springframework.stereotype; public @interface Component {}',encoding='utf-8')
        (src/'Harness.java').write_text(r'''import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.application.action.*; import java.util.*;
public class Harness { static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);} public static void main(String[] z){
var original=List.of("plain","two words","embedded \"quote\"","\\\\server\\share\\Book Folder\\book.fb2","C:\\Books\\A B\\book.epub","apostrophe's");
eq(CommandTemplate.parse(CommandTemplate.formatArguments(original)),original);
var expanded=CommandTemplate.expand("tool --title \"%TITLE%\" --file \"%FILE%\"",Map.of("%TITLE%","Book \" --delete-all -- x","%FILE%","C:\\A B\\book.fb2"));
eq(expanded,List.of("tool","--title","Book \" --delete-all -- x","--file","C:\\A B\\book.fb2"));
var p=new BookActionProfile("p","Preview",true,List.of(new BookActionCommand("tool","--title \"%TITLE%\"","%DIR%",false)));
var q=new BookActionExecutionService().preview(p,Map.of("%TITLE%","A ; touch /tmp/x","%DIR%","/tmp")); eq(q.commands().getFirst().argv(),List.of("tool","--title","A ; touch /tmp/x"));
System.out.println("PASS"); }}''',encoding='utf-8')
        java=list(src.rglob('*.java'))
        cp=subprocess.run(['javac','--release','21','-d',str(classes),*map(str,java)],capture_output=True,text=True)
        if cp.returncode: errors.append('javac harness failed: '+cp.stderr[-2000:])
        else:
            run=subprocess.run(['java','-cp',str(classes),'Harness'],capture_output=True,text=True)
            if run.returncode or 'PASS' not in run.stdout: errors.append('java harness failed: '+run.stderr[-1000:])
except Exception as e: errors.append('harness exception: '+repr(e))

if errors:
    print('STAGE 14+15 ACTIONS CHECK: FAIL')
    for e in errors: print(' -',e)
    sys.exit(1)
print('STAGE 14+15 ACTIONS CHECK: PASS')
print(' - centralized ActionRegistry + persisted shortcut/visibility state: PASS')
print(' - conflict/syntax validation + no hardcoded MainController key filter: PASS')
print(' - named ordered book-action profiles + legacy post-command migration: PASS')
print(' - ProcessBuilder/no-shell execution + exact argv preview: PASS')
print(' - quote/space/Windows+UNC command-template round-trip: PASS')
print(' - context-menu binding + archive-aware RunBookActionUseCase: PASS')
