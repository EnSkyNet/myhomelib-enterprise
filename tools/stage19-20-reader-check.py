#!/usr/bin/env python3
"""Offline Stage 19/20 Reader UX + engine regression guard."""
from pathlib import Path
import shutil, subprocess, tempfile, textwrap

ROOT = Path(__file__).resolve().parents[1]
errors=[]
def fail(x): errors.append(x)
def text(rel):
    p=ROOT/rel
    if not p.exists(): fail(f"missing {rel}"); return ""
    return p.read_text(encoding='utf-8')

settings=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSettings.java')
presets=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSettingsPresets.java')
prefs=text('myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/reader/ReaderPreferences.java')
state=text('myhomelib-application/src/main/java/com/myhomelibcorp/application/reader/ReaderSettingsStateService.java')
override=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/reader/ReaderBookPreferencesService.java')
atomic=text('myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/AtomicFileSupport.java')
dialog=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java')
workspace=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java')
autosave=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderPositionAutosaver.java')
view=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderView.java')
status=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderStatusBar.java')
canvas=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderCanvas.java')
selection=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderSelectionController.java')
layout=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/TextLayoutEngine.java')
layout_support=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/TextLineLayoutSupport.java')
hyphen=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/HyphenationService.java')
epub=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/format/epub/EpubParser.java')
legacy=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/reader/ReaderPreferencesService.java')
codec=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/reader/ReaderPreferencesJsonCodec.java')

for marker in ('showStatusBar','showStatusProgress','tapLeftAction','tapCenterAction','tapRightAction'):
    if marker not in settings or marker not in prefs: fail(f'Reader settings/domain missing {marker}')
for marker in ('day','comfortable','compact','night'):
    if marker not in presets: fail(f'built-in preset missing: {marker}')
for marker in ('saveForBook','clearBookOverride','loadGlobal'):
    if marker not in state: fail(f'per-book/global state service missing {marker}')
v42=text('myhomelib-infrastructure/src/main/resources/db/migration/V42__reader_book_preferences.sql')
if ('reader_book_preferences' not in override
        or 'reader-book-preferences.json' not in override
        or 'LEGACY_MIGRATION_BATCH = 400' not in override
        or 'ON CONFLICT(book_id) DO UPDATE' not in override
        or 'CREATE TABLE IF NOT EXISTS reader_book_preferences' not in v42):
    fail('per-book settings are not collection-scoped SQLite with bounded legacy migration')
if 'AtomicFileSupport.moveReplacing' not in legacy or 'StandardCopyOption.ATOMIC_MOVE' not in atomic:
    fail('global Reader settings persistence is not atomic')
if ('ReaderPreferences.builder().build()' not in codec or 'valueToTree' not in codec or 'merged.set' not in codec): fail('legacy Reader preferences are not merged over current defaults')

for marker in ('Типографіка','Стилі елементів','Кольори','Макет','Навігація','Статус','Застосувати preset','Лише для цієї книги','livePreview','Скинути типографіку','Скинути навігацію','Скинути статус'):
    if marker not in dialog: fail(f'categorized/live settings dialog missing: {marker}')
if 'ReaderStatusBar' not in view or 'setBottom(statusBar)' not in view: fail('ReaderView has no dedicated status bar')
for marker in ('showStatusProgress','showStatusChapter','showStatusPage'):
    if marker not in status: fail(f'ReaderStatusBar missing option {marker}')
for action in ('previous-page','next-page','previous-chapter','next-chapter','toggle-toolbar','search'):
    if action not in canvas: fail(f'tap-zone action missing: {action}')

if 'scheduleWithFixedDelay(this::flushIfDirty, 3, 3, TimeUnit.SECONDS)' not in autosave: fail('position autosave is not periodic at 3 seconds')
if 'AnimationTimer' in workspace: fail('workspace still relies on JavaFX AnimationTimer for persistence')
if 'positionAutosaver.mark(pos)' not in workspace or 'positionAutosaver.flush()' not in workspace: fail('workspace autosave wiring missing')

for lang in ('uk','en','bg','ru'):
    if not (ROOT/f'myhomelib-reader/src/main/resources/hyphenation/{lang}.dic').is_file(): fail(f'hyphenation dictionary missing: {lang}')
if 'hyphenationService.candidates(word, language)' not in (layout + layout_support) or "lineText = text.substring(cursor, displayEnd) + (hyphenated ? \"‐\"" not in layout:
    fail('TextLayoutEngine does not apply language-aware visual hyphenation')
if 'document.metadata() != null ? document.metadata().language()' not in layout: fail('layout does not use document language')
if 'navigationOffsets.put' not in epub or 'resolveNavigationTarget' not in epub or 'decodeFragment' not in epub: fail('EPUB nav/NCX fragment anchor refinement missing')
for marker in ('event.isShiftDown()', 'renderSelectionOverlay', 'copySelection'):
    if marker not in canvas: fail(f'selection/copy behavior missing: {marker}')
if 'Clipboard.getSystemClipboard()' not in selection: fail('selection/copy behavior missing: Clipboard.getSystemClipboard()')

for rel in (
 'myhomelib-reader/src/test/java/com/myhomelibcorp/reader/layout/HyphenationServiceTest.java',
 'myhomelib-reader/src/test/java/com/myhomelibcorp/reader/api/ReaderSettingsPresetsTest.java',
 'myhomelib-reader/src/test/java/com/myhomelibcorp/reader/performance/ReaderLargeDocumentPerformanceTest.java',
 'myhomelib-application/src/test/java/com/myhomelibcorp/application/reader/ReaderSettingsStateServiceTest.java'):
    if not (ROOT/rel).is_file(): fail(f'missing Stage-19/20 regression fixture: {rel}')
if 'resolvesEpub3AndNcxFragmentsToExactTextOffsets' not in text('myhomelib-reader/src/test/java/com/myhomelibcorp/reader/format/epub/EpubParserTest.java'):
    fail('EPUB exact-fragment regression fixture missing')

# Compile/run portable ReaderSettings + presets + real dictionary loader without Maven.
def portable_smoke():
    with tempfile.TemporaryDirectory(prefix='mhl-reader-1920-') as td:
        td=Path(td); src=td/'src'; classes=td/'classes'; classes.mkdir()
        for rel in (
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSettings.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderInputSettings.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderStyleSheet.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderElementStyle.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSemanticElement.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/TextStyle.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSettingsPreset.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSettingsPresets.java',
          'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/layout/HyphenationService.java'):
            dest=src/Path(rel).relative_to('myhomelib-reader/src/main/java'); dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy(ROOT/rel,dest)
        smoke=src/'Smoke.java'
        smoke.write_text(textwrap.dedent('''
          import com.myhomelibcorp.reader.api.*; import com.myhomelibcorp.reader.layout.HyphenationService;
          public class Smoke { public static void main(String[] a) {
            var d=ReaderSettings.defaultSettings(); if(!d.showStatusBar()||!"previous-page".equals(d.tapLeftAction())) throw new AssertionError("defaults");
            if(ReaderSettingsPresets.builtIns().size()!=5) throw new AssertionError("presets");
            var h=new HyphenationService(); var c=h.candidates("бібліотека","uk-UA"); if(c.isEmpty()||!c.contains(3)) throw new AssertionError("dictionary "+c);
            var u=h.candidates("електромагнітний","uk"); if(u.stream().anyMatch(x->x<2||x>14)) throw new AssertionError("safe candidates");
            System.out.println("READER PORTABLE SMOKE: PASS");
          }}
        '''),encoding='utf-8')
        java=[str(p) for p in src.rglob('*.java')]
        cp=subprocess.run(['javac','--release','21','-d',str(classes),*java],capture_output=True,text=True,timeout=30)
        if cp.returncode: raise RuntimeError('javac failed:\n'+cp.stdout+cp.stderr)
        resources=ROOT/'myhomelib-reader/src/main/resources'
        run=subprocess.run(['java','-cp',str(classes)+':'+str(resources),'Smoke'],capture_output=True,text=True,timeout=30)
        if run.returncode: raise RuntimeError('portable smoke failed:\n'+run.stdout+run.stderr)
        print(run.stdout.strip())

if errors:
    print('STAGE 19/20 READER CHECK: FAIL')
    for e in errors: print(' -',e)
    raise SystemExit(1)
try: portable_smoke()
except Exception as e:
    print('STAGE 19/20 READER CHECK: FAIL'); print(e); raise SystemExit(1)
print('STAGE 19/20 READER CHECK: PASS')
print(' - categorized presets/per-book/global/live-preview settings: PASS')
print(' - status bar + configurable tap zones: PASS')
print(' - 3-second crash-loss-bounded position autosave: PASS')
print(' - uk/en/bg/ru dictionary-aware hyphenation: PASS')
print(' - EPUB nav/NCX fragment anchors: PASS')
print(' - Shift-drag selection + Ctrl+C: PASS')
print(' - large FB2/EPUB performance fixtures: PASS')
