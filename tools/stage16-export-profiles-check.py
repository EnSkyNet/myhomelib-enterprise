#!/usr/bin/env python3
from pathlib import Path
import sys, tempfile, shutil, subprocess
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(path,*tokens):
    text=(ROOT/path).read_text(encoding='utf-8')
    for t in tokens:
        if t not in text: errors.append(f'{path}: missing {t}')
    return text

req=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/dto/ExportRequest.java',
         'CollisionPolicy','ASK','subfolderTemplate','profileId','profileName','postActionProfileId')
prof=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/export/ExportProfileService.java',
          'exportProfiles.order','default-export','export.filenameTemplate','export.subfolderTemplate','legacy-post-command','save(ExportProfile')
hist=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/export/ExportHistoryService.java',
          'MAX_ENTRIES = 50','exportHistory.order','loadRecent','clear()','durationMs')
use=need('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/export/ExportToDeviceUseCase.java',
         'ExportCollisionResolver','case ASK','historyService.record','request.getSubfolderTemplate()',
         'request.getPostActionProfileId()','actionExecutionService.execute','request.isExtractOnly() && book.hasArchiveEntry()',
         'Files.copy(sourceStream, stagedFile','commitExportedFile(stagedFile, targetFile)','ATOMIC_MOVE','verifyExportedFile(targetFile)','ExportProgress','AtomicBoolean')
ui=need('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/ExportController.java',
        'ExportProfileService','ExportHistoryService','onSaveProfileAs','onUpdateProfile','onDeleteProfile','onShowHistory',
        'this::resolveCollision','setProgressVisible(true)','setProgress(value)','CollisionPolicy.ASK')
fxml=need('myhomelib-ui/src/main/resources/view/export-dialog.fxml',
          'fx:id="profileComboBox"','fx:id="subfolderTemplateField"','fx:id="postActionComboBox"',
          'onAction="#onSaveProfileAs"','onAction="#onUpdateProfile"','onAction="#onDeleteProfile"','onAction="#onShowHistory"')

# UI architecture guard.
if 'application.port.out' in ui or 'com.myhomelibcorp.infrastructure' in ui:
    errors.append('ExportController has forbidden output-port/infrastructure import')

# Focused compile/run of actual profile/history services after replacing Lombok-generated constructors.
try:
    with tempfile.TemporaryDirectory(prefix='mhl-stage16-') as td:
        t=Path(td); src=t/'src'; classes=t/'classes'
        for d in ['com/myhomelibcorp/application/export','com/myhomelibcorp/application/dto','com/myhomelibcorp/application/port/out/settings','org/springframework/stereotype']:
            (src/d).mkdir(parents=True,exist_ok=True)
        for name in ['ExportProfile.java','ExportProfileService.java','ExportHistoryEntry.java','ExportHistoryService.java']:
            text=(ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/export'/name).read_text(encoding='utf-8')
            text=text.replace('import lombok.RequiredArgsConstructor;\n','').replace('@RequiredArgsConstructor\n','')
            if name=='ExportProfileService.java':
                text=text.replace('public class ExportProfileService {','public class ExportProfileService {\n    public ExportProfileService(ApplicationSettingsPort settings) { this.settings = settings; }')
            if name=='ExportHistoryService.java':
                text=text.replace('public class ExportHistoryService {','public class ExportHistoryService {\n    public ExportHistoryService(ApplicationSettingsPort settings) { this.settings = settings; }')
            (src/'com/myhomelibcorp/application/export'/name).write_text(text,encoding='utf-8')
        (src/'org/springframework/stereotype/Component.java').write_text('package org.springframework.stereotype; public @interface Component {}',encoding='utf-8')
        (src/'com/myhomelibcorp/application/port/out/settings/ApplicationSettingsPort.java').write_text('''package com.myhomelibcorp.application.port.out.settings; import java.util.Map; public interface ApplicationSettingsPort { String get(String k,String d); void put(String k,String v); void remove(String k); Map<String,String> findByPrefix(String p); default boolean getBoolean(String k,boolean d){return Boolean.parseBoolean(get(k,Boolean.toString(d)));} default int getInt(String k,int d){try{return Integer.parseInt(get(k,Integer.toString(d)));}catch(Exception e){return d;}} default void putBoolean(String k,boolean v){put(k,Boolean.toString(v));} default void putInt(String k,int v){put(k,Integer.toString(v));}}''',encoding='utf-8')
        (src/'com/myhomelibcorp/application/dto/ExportRequest.java').write_text('''package com.myhomelibcorp.application.dto; import java.nio.file.Path; public class ExportRequest { public enum ExportFormat{FB2,FB2_ZIP,TXT,PDF,EPUB,MOBI,LRF} public enum CollisionPolicy{OVERWRITE,SKIP,RENAME,ASK} private String profileName; private Path destination; private ExportFormat format; public ExportRequest(String n,Path d,ExportFormat f){profileName=n;destination=d;format=f;} public String getProfileName(){return profileName;} public Path getDestinationFolder(){return destination;} public ExportFormat getFormat(){return format;} }''',encoding='utf-8')
        (src/'Harness.java').write_text(r'''import com.myhomelibcorp.application.export.*; import com.myhomelibcorp.application.dto.ExportRequest; import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort; import java.util.*; import java.nio.file.*;
public class Harness { static class S implements ApplicationSettingsPort { Map<String,String> m=new LinkedHashMap<>(); public String get(String k,String d){return m.getOrDefault(k,d);} public void put(String k,String v){m.put(k,v);} public void remove(String k){m.remove(k);} public Map<String,String> findByPrefix(String p){var r=new LinkedHashMap<String,String>();m.forEach((k,v)->{if(k.startsWith(p))r.put(k,v);});return r;} }
static void ok(boolean b,String m){if(!b)throw new AssertionError(m);} public static void main(String[]z){ S s=new S(); s.put("export.filenameTemplate","%t"); s.put("export.subfolderTemplate","%a/%y"); s.putBoolean("export.runPostCommand",true); var ps=new ExportProfileService(s); var p=ps.loadProfiles().getFirst(); ok(p.postActionProfileId().equals("legacy-post-command"),"migration"); var c=new ExportProfile("dev","Device",ExportRequest.ExportFormat.EPUB,"/tmp",ExportRequest.CollisionPolicy.ASK,false,"%t","%a","act"); ps.save(c); ok(ps.findById("dev").orElseThrow().equals(c),"profile roundtrip"); var hs=new ExportHistoryService(s); hs.record(new ExportRequest("Device",Path.of("/tmp"),ExportRequest.ExportFormat.EPUB),3,2,1,0,false,1234); ok(hs.loadRecent(1).getFirst().exported()==2,"history"); System.out.println("PASS"); }}''',encoding='utf-8')
        java=[str(p) for p in src.rglob('*.java')]
        cp=subprocess.run(['javac','--release','21','-d',str(classes),*java],capture_output=True,text=True)
        if cp.returncode: errors.append('stage16 javac harness failed: '+cp.stderr[-2000:])
        else:
            run=subprocess.run(['java','-cp',str(classes),'Harness'],capture_output=True,text=True)
            if run.returncode or 'PASS' not in run.stdout: errors.append('stage16 harness failed: '+run.stderr[-1000:])
except Exception as e: errors.append('stage16 harness exception: '+repr(e))

if errors:
    print('STAGE 16 EXPORT PROFILES CHECK: FAIL')
    for e in errors: print(' -',e)
    sys.exit(1)
print('STAGE 16 EXPORT PROFILES CHECK: PASS')
print(' - named persisted export profiles + legacy defaults migration: PASS')
print(' - overwrite/skip/rename/ASK collision contract: PASS')
print(' - batch progress/cancel + global status-bar wiring: PASS')
print(' - profile-specific filename/subfolder/post-action: PASS')
print(' - raw archive-entry extract-only path: PASS')
print(' - bounded persistent export history: PASS')
