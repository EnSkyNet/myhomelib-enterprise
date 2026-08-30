#!/usr/bin/env python3
"""Offline Stage 25B targeted reader-refactor regression guard."""
from pathlib import Path
import shutil, subprocess, tempfile, textwrap, sys

ROOT = Path(__file__).resolve().parents[1]
errors=[]
def need(c,m):
    if not c: errors.append(m)
def text(rel):
    p=ROOT/rel
    if not p.exists(): errors.append(f'missing {rel}'); return ''
    return p.read_text(encoding='utf-8')

canvas=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderCanvas.java')
selection=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderSelectionController.java')
history=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderPageHistory.java')
layout=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/TextLayoutEngine.java')
lines=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/TextLineLayoutSupport.java')
parser=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/format/fb2/Fb2StreamingParser.java')
parse_support=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/format/fb2/Fb2ParseSupport.java')

need(len(canvas.splitlines()) <= 720, f'ReaderCanvas still too large: {len(canvas.splitlines())}')
need(len(layout.splitlines()) <= 400, f'TextLayoutEngine still too large: {len(layout.splitlines())}')
need(len(parser.splitlines()) <= 620, f'Fb2StreamingParser still too large: {len(parser.splitlines())}')
need('ReaderSelectionController selectionController' in canvas and 'ReaderPageHistory pageHistory' in canvas,
     'ReaderCanvas selection/history extraction missing')
need('selectionAnchorOffset' not in canvas and 'selectionFocusOffset' not in canvas,
     'ReaderCanvas still owns selection offset state')
need('TextLineLayoutSupport lineSupport' in layout and 'hyphenationService.candidates(word, language)' in lines,
     'TextLayoutEngine line-breaking extraction missing')
need('private LineBreak findLineEnd' not in layout and 'private List<TextRunLayout> buildVisualRuns' not in layout,
     'TextLayoutEngine still owns extracted line-breaking/run-composition logic')
need('import static com.myhomelibcorp.reader.format.fb2.Fb2ParseSupport.*;' in parser,
     'Fb2StreamingParser parse-support extraction missing')
for old in ('private TextStyle styleForParagraph', 'private boolean appendNormalized', 'private String buildAuthor'):
    need(old not in parser, f'Fb2StreamingParser still owns utility: {old}')
for marker in ('copyToClipboard()', 'renderOverlay(PageLayout page)', 'hitTestOffset'):
    need(marker in selection, f'ReaderSelectionController missing {marker}')
for rel in (
    'myhomelib-reader/src/test/java/com/myhomelibcorp/reader/render/javafx/ReaderPageHistoryTest.java',
    'myhomelib-reader/src/test/java/com/myhomelibcorp/reader/layout/TextLineLayoutSupportTest.java',
    'myhomelib-reader/src/test/java/com/myhomelibcorp/reader/format/fb2/Fb2ParseSupportTest.java'):
    need((ROOT/rel).is_file(), f'missing refactor regression fixture: {rel}')
need((ROOT/'docs/performance-baseline.json').is_file(), 'Stage 24 performance baseline missing before reader refactor')

# Compile/run the extracted pure-JDK helpers without Maven.
def compile_history():
    with tempfile.TemporaryDirectory(prefix='mhl-25b-history-') as td:
        td=Path(td); src=td/'src'; out=td/'out'; out.mkdir();
        for rel in (
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderPosition.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderPageHistory.java'):
            dest=src/Path(rel).relative_to('myhomelib-reader/src/main/java'); dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy(ROOT/rel,dest)
        smoke=src/'com/myhomelibcorp/reader/render/javafx/Smoke.java'; smoke.parent.mkdir(parents=True,exist_ok=True)
        smoke.write_text(textwrap.dedent('''
          package com.myhomelibcorp.reader.render.javafx;
          import com.myhomelibcorp.reader.api.ReaderPosition;
          public class Smoke { public static void main(String[] a) {
            var h=new ReaderPageHistory(2); h.push(new ReaderPosition(0,1,0,0)); h.push(new ReaderPosition(0,2,0,0)); h.push(new ReaderPosition(0,3,0,0));
            if(h.pollLast().textOffset()!=3 || h.pollLast().textOffset()!=2 || h.pollLast()!=null) throw new AssertionError();
          }}
        '''),encoding='utf-8')
        cp=subprocess.run(['javac','--release','21','-d',str(out),*[str(p) for p in src.rglob('*.java')]],capture_output=True,text=True,timeout=30)
        if cp.returncode: raise RuntimeError('history javac failed:\n'+cp.stdout+cp.stderr)
        run=subprocess.run(['java','-cp',str(out),'com.myhomelibcorp.reader.render.javafx.Smoke'],capture_output=True,text=True,timeout=30)
        if run.returncode: raise RuntimeError('history smoke failed:\n'+run.stdout+run.stderr)

def compile_line_support():
    with tempfile.TemporaryDirectory(prefix='mhl-25b-lines-') as td:
        td=Path(td); src=td/'src'; out=td/'out'; out.mkdir()
        rels=(
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSettings.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderInputSettings.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/TextStyle.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/StyleSpan.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/model/TextRunLayout.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/FontMetricsProvider.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/HyphenationService.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/TextLineLayoutSupport.java')
        for rel in rels:
            dest=src/Path(rel).relative_to('myhomelib-reader/src/main/java'); dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy(ROOT/rel,dest)
        smoke=src/'com/myhomelibcorp/reader/layout/Smoke.java'; smoke.parent.mkdir(parents=True,exist_ok=True)
        smoke.write_text(textwrap.dedent('''
          package com.myhomelibcorp.reader.layout;
          import com.myhomelibcorp.reader.api.*; import java.util.*;
          public class Smoke {
            static final class M implements FontMetricsProvider {
              public float getCharWidth(char c,TextStyle s,float z){return 10;} public float getStringWidth(String t,TextStyle s,float z){return t.length()*10;}
              public float getLineHeight(TextStyle s,float z,float l){return 20;} public float getFontHeight(TextStyle s,float z){return 18;}
              public float getAverageCharWidth(TextStyle s,float z){return 10;} public float getSpaceWidth(TextStyle s,float z){return 10;}
              public boolean isFontSupported(String f){return true;} public List<String> getAvailableFonts(){return List.of("Georgia");}
              public FontMetricsProvider withSettings(ReaderSettings s){return this;}
            }
            public static void main(String[] a){ var s=new TextLineLayoutSupport(new M(),ReaderSettings.defaultSettings()); var b=s.findLineEnd("бібліотека",0,45,TextStyle.NORMAL,18,List.of(),"uk"); if(!b.hyphenated()||b.end()!=3) throw new AssertionError(b); }
          }
        '''),encoding='utf-8')
        cp=subprocess.run(['javac','--release','21','-d',str(out),*[str(p) for p in src.rglob('*.java')]],capture_output=True,text=True,timeout=30)
        if cp.returncode: raise RuntimeError('line support javac failed:\n'+cp.stdout+cp.stderr)
        resources=ROOT/'myhomelib-reader/src/main/resources'
        run=subprocess.run(['java','-cp',str(out)+':'+str(resources),'com.myhomelibcorp.reader.layout.Smoke'],capture_output=True,text=True,timeout=30)
        if run.returncode: raise RuntimeError('line support smoke failed:\n'+run.stdout+run.stderr)

def compile_parse_support():
    with tempfile.TemporaryDirectory(prefix='mhl-25b-fb2-') as td:
        td=Path(td); src=td/'src'; out=td/'out'; out.mkdir()
        rels=(
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/TextStyle.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/StyleSpan.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ParagraphInfo.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/TextFragment.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/TextStorage.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/core/text/TextStorageImpl.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/format/fb2/Fb2ParseSupport.java')
        for rel in rels:
            dest=src/Path(rel).relative_to('myhomelib-reader/src/main/java'); dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy(ROOT/rel,dest)
        smoke=src/'com/myhomelibcorp/reader/format/fb2/Smoke.java'; smoke.parent.mkdir(parents=True,exist_ok=True)
        smoke.write_text(textwrap.dedent('''
          package com.myhomelibcorp.reader.format.fb2;
          import com.myhomelibcorp.reader.api.*; import com.myhomelibcorp.reader.core.text.TextStorageImpl;
          public class Smoke { public static void main(String[] a) { var t=new TextStorageImpl(); boolean sp=Fb2ParseSupport.appendNormalized(t,"  a\\n b  ",TextStyle.BOLD,false); if(!" a b ".equals(t.getFullText())||!sp) throw new AssertionError(t.getFullText()); if(Fb2ParseSupport.combineInlineStyles(TextStyle.BOLD,TextStyle.ITALIC)!=TextStyle.BOLD_ITALIC) throw new AssertionError(); }}
        '''),encoding='utf-8')
        cp=subprocess.run(['javac','--release','21','-d',str(out),*[str(p) for p in src.rglob('*.java')]],capture_output=True,text=True,timeout=30)
        if cp.returncode: raise RuntimeError('FB2 support javac failed:\n'+cp.stdout+cp.stderr)
        run=subprocess.run(['java','-cp',str(out),'com.myhomelibcorp.reader.format.fb2.Smoke'],capture_output=True,text=True,timeout=30)
        if run.returncode: raise RuntimeError('FB2 support smoke failed:\n'+run.stdout+run.stderr)

if errors:
    print('STAGE 25B READER REFACTOR CHECK: FAIL')
    for e in errors: print(' -',e)
    sys.exit(1)
try:
    compile_history(); compile_line_support(); compile_parse_support()
except Exception as e:
    print('STAGE 25B READER REFACTOR CHECK: FAIL')
    print(e)
    sys.exit(1)
print('STAGE 25B READER REFACTOR CHECK: PASS')
print(f' - ReaderCanvas: {len(canvas.splitlines())} lines; selection/history state extracted')
print(f' - TextLayoutEngine: {len(layout.splitlines())} lines; line breaking/run composition extracted')
print(f' - Fb2StreamingParser: {len(parser.splitlines())} lines; token/text utilities extracted')
print(' - extracted pure-JDK helpers compile/run without Maven: PASS')
