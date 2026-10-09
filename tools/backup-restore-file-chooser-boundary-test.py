#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
problems=[]
for rel in [
 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/BackupController.java',
 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/RestoreController.java']:
    text=(root/rel).read_text(encoding='utf-8')
    if 'new DirectoryChooser(' in text or 'import javafx.stage.DirectoryChooser;' in text:
        problems.append(rel+': bypasses FileChooserService')
    if 'FileChooserService' not in text:
        problems.append(rel+': missing FileChooserService boundary')
if problems:
    print('FAIL: backup/restore native chooser boundary')
    print('\n'.join(' - '+p for p in problems))
    sys.exit(1)
print('PASS: backup/restore use FileChooserService boundary')
