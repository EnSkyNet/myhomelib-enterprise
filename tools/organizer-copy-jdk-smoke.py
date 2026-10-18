#!/usr/bin/env python3
"""Compile real Java file/organizer publication classes and run crash-safety smoke tests.

Supplemental evidence only. Does not replace Maven/JUnit or Windows acceptance.
"""
from __future__ import annotations
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
CLASSES = [
    'myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/SafeFilePrimitives.java',
    'myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/NoLinkPathSafety.java',
    'myhomelib-library-organizer/src/main/java/com/myhomelibcorp/organizer/fs/OrganizerPathSafety.java',
    'myhomelib-library-organizer/src/main/java/com/myhomelibcorp/organizer/apply/OrganizerFileIntegritySupport.java',
    'myhomelib-library-organizer/src/main/java/com/myhomelibcorp/organizer/apply/OrganizerTargetPublisher.java',
    'tools/java-smoke/com/myhomelibcorp/shared/util/ManagedFileCopySmoke.java',
    'tools/java-smoke/com/myhomelibcorp/organizer/apply/OrganizerPublishSmoke.java',
]


def main():
    with tempfile.TemporaryDirectory(prefix='mhl-organizer-copy-jdk-') as temp:
        out = pathlib.Path(temp)
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(out), *map(lambda f: str(ROOT / f), CLASSES)], check=True)
        for cls in ('com.myhomelibcorp.shared.util.ManagedFileCopySmoke',
                    'com.myhomelibcorp.organizer.apply.OrganizerPublishSmoke'):
            subprocess.run(['java', '-cp', str(out), cls], check=True)
    print('ORGANIZER_COPY_JDK_SMOKE: PASS')


if __name__ == '__main__':
    main()
