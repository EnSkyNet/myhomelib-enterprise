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
reader_view=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/javafx/ReaderView.java')
reader_controller=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java')
theme=text('myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderTheme.java')
features=text('MYHOMELIB-FEATURES.md')

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
cycle_body = canvas.split('public void cycleTheme()',1)[1].split('public void updateTheme',1)[0] if 'public void cycleTheme()' in canvas else ''
need('notifySettingsChanged();' in cycle_body,
     'quick theme cycle must publish a settings change')
need('canvas.setOnSettingsChanged(settings ->' in reader_view and 'onSettingsChanged.accept(settings);' in reader_view,
     'ReaderView must forward canvas settings changes')
need('readerView.setOnSettingsChanged(this::persistReaderSettings);' in reader_controller,
     'Reader workspace must persist quick settings changes')
for marker in ('Шрифт','Розмір','Міжрядковий інтервал','Відступ першого рядка (em)','Вирівнювання','Тема',
               'Дві сторінки','Автопрокрутка','Швидкість автопрокрутки','Показувати панель інструментів',
               'Показувати нижній status bar'):
    need(marker in dialog, f'Reader settings GUI missing reachable control: {marker}')
for marker in ('Desktop Reader behavior is intentionally not a claim of complete Android/iOS or AlReaderX feature parity',
               'Ukrainian/English/Bulgarian/Russian hyphenation dictionaries',
               'one/two-page mode',
               'autoscroll'):
    need(marker in features, f'current Reader feature contract missing: {marker}')

if errors:
    print('READER ALREADERX GAP CHECK: FAILED')
    for error in errors: print(' -',error)
    sys.exit(1)
print('READER ALREADERX GAP CHECK: PASS')
print(' - current production presets/test are aligned')
print(' - two-page/navigation/input/color/autoscroll wiring is documented consistently')
print(' - current desktop parity boundary is explicitly documented')
