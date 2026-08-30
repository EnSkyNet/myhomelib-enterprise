#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors=[]
def need(cond,msg):
    if not cond: errors.append(msg)
def text(rel):
    p=ROOT/rel
    if not p.exists(): errors.append(f'missing {rel}'); return ''
    return p.read_text(encoding='utf-8')

presets=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderSettingsPresets.java')
preset_test=text('myhomelib-reader/src/test/java/com/myhomelibcorp/reader/api/ReaderSettingsPresetsTest.java')
input_settings=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderInputSettings.java')
canvas=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderCanvas.java')
autoscroll=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/AutoScrollController.java')
dialog=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderSettingsDialog.java')
theme=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderTheme.java')
gap=text('READER-ALREADERX-GAP-v7.1.md')

for marker in ('"day"','"night"'):
    need(marker in presets, f'production presets missing {marker}')
need('containsExactly("default", "day", "comfortable", "compact", "night")' in preset_test,
     'ReaderSettingsPresetsTest is stale relative to production presets')
for marker in ('longTopLeft','longBottomRight','swipeLeft','swipeDown','pinchZoom'):
    need(marker in input_settings, f'input map missing {marker}')
for marker in ('goToPercent(double percent)','goToPage(int page)','previousChapter()','nextChapter()','autoTwoPageLandscape'):
    need(marker in canvas, f'ReaderCanvas parity path missing {marker}')
need('nextPageAction.run()' in autoscroll and 'AnimationTimer' in autoscroll,
     'expected page-step autoscroll implementation missing')
for marker in ('Колір фону','Колір тексту','backgroundColor','textColor','mergeReaderColors'):
    need(marker in dialog, f'custom reader color UI missing {marker}')
for marker in ('--reader-background','--reader-foreground'):
    need(marker in theme or marker in dialog, f'custom color persistence missing {marker}')
for marker in ('PARTIAL BY DESIGN','DEFERRED P2','NOT APPLICABLE','Hyphenation for 20 languages'):
    need(marker in gap, f'gap audit classification missing: {marker}')

if errors:
    print('READER ALREADERX GAP CHECK: FAILED')
    for error in errors: print(' -',error)
    sys.exit(1)
print('READER ALREADERX GAP CHECK: PASS')
print(' - current production presets/test are aligned')
print(' - two-page/navigation/input/color/autoscroll wiring is classified consistently')
print(' - desktop-only/deferred gaps are explicitly documented')
