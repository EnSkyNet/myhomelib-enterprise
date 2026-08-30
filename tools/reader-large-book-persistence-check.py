#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
persistence=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderPersistenceService.java').read_text()
autosaver=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/ReaderPositionAutosaver.java').read_text()
controller=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java').read_text()
if 'getPercent(1000)' in persistence:
    raise SystemExit('FAIL: reader progress still uses fake 1000-char total')
if 'savePosition(String bookId, ReaderPosition position, long totalTextLength)' not in persistence:
    raise SystemExit('FAIL: persistence must receive real document length')
if 'start(String bookId, long totalTextLength)' not in autosaver:
    raise SystemExit('FAIL: autosaver must retain current document length')
if 'positionAutosaver.start(currentBookId.asString(), totalTextLength)' not in controller:
    raise SystemExit('FAIL: workspace must start autosave with actual document length')

src=ROOT/'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/api/ReaderPosition.java'
with tempfile.TemporaryDirectory(prefix='mhl-reader-percent-') as td:
    td=Path(td)
    test=td/'ReaderPercentSmoke.java'
    test.write_text('''\nimport com.myhomelibcorp.reader.api.ReaderPosition;\npublic class ReaderPercentSmoke {\n  public static void main(String[] args) {\n    ReaderPosition p = new ReaderPosition(1, 500_000L, 10, 5);\n    double percent = p.getPercent(1_000_000L);\n    if (Math.abs(percent - 50.0) > 0.0001) throw new AssertionError(percent);\n    if (p.getPercent(2_000_000L) >= 100.0) throw new AssertionError("large-book percent saturated");\n  }\n}\n''')
    subprocess.run(['javac','-d',str(td),str(src),str(test)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    subprocess.run(['java','-cp',str(td),'ReaderPercentSmoke'],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
print('READER LARGE-BOOK PERSISTENCE CHECK: PASS')
print(' - autosave percent uses actual document length, not a 1000-char surrogate')
print(' - large offsets remain proportional instead of saturating at 100%')
