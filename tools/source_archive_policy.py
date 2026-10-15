#!/usr/bin/env python3
"""Bounded source ZIP validation with portable paths and explicit Unix modes."""
import argparse,os,shutil,stat,zipfile
from pathlib import Path,PurePosixPath
from zip_evidence_safety import validate_infos
EXCLUDED_DIRS={'.git','.mvn','target','dist','verification','.idea','.gradle','__pycache__','.pytest_cache'}
EXCLUDED_FILES={'.DS_Store','Thumbs.db','mvnw','mvnw.cmd'}
def excluded(path):return bool(set(path.parts)&EXCLUDED_DIRS) or path.name in EXCLUDED_FILES
def source_files(root):
    for directory,dirs,files in os.walk(root,followlinks=False):
        parent=Path(directory);dirs[:]=sorted(d for d in dirs if not excluded((parent/d).relative_to(root)))
        for name in dirs+sorted(files):
            p=parent/name
            if excluded(p.relative_to(root)):continue
            mode=p.lstat().st_mode
            if not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):raise ValueError(f'source forbids links/special files: {p}')
            if stat.S_ISREG(mode):
                if os.name!='nt' and p.suffix=='.sh' and stat.S_IMODE(mode)!=0o755:raise ValueError(f'shell requires mode 0755: {p}')
                yield p

def validate_archive(archive,top_dir=None):
    with zipfile.ZipFile(archive) as z:
        infos=z.infolist()
        if len(infos)>50000:raise ValueError('too many ZIP entries')
        validate_infos(infos,label='source ZIP',max_files=50000,max_member_bytes=256*1024*1024,max_total_bytes=1024*1024*1024)
        seen,files,parents,roots=set(),set(),set(),set()
        for i in infos:
            raw=i.filename;name=raw.rstrip('/');p=PurePosixPath(name);mode=i.external_attr>>16
            if not name or '\\' in raw or ':' in raw or name!=str(p) or p.is_absolute() or '..' in p.parts or any(s.endswith(('.', ' ')) for s in p.parts) or any(ord(c)<32 for c in raw):raise ValueError(f'unsafe ZIP path: {raw!r}')
            key=name.casefold()
            if key in seen:raise ValueError('duplicate/case collision')
            seen.add(key);roots.add(p.parts[0]);permissions=stat.S_IMODE(mode)
            if i.create_system!=3 or stat.S_IFMT(mode)!=(stat.S_IFDIR if i.is_dir() else stat.S_IFREG):raise ValueError('ordinary Unix file/directory metadata required')
            if permissions not in {0o644,0o755} or ((i.is_dir() or p.suffix=='.sh') and permissions!=0o755):raise ValueError('invalid source mode')
            if i.flag_bits&1:raise ValueError('encrypted source member')
            if not i.is_dir():files.add(key)
            parents.update(str(a).casefold() for a in p.parents if str(a)!='.')
        if files&parents:raise ValueError('file/directory collision')
        if len(roots)!=1:raise ValueError('one root directory required')
        actual=next(iter(roots))
        if top_dir is not None and actual!=top_dir:raise ValueError('unexpected root')
        if actual.casefold() in files:raise ValueError('root must be directory')
        return actual

def extract_archive(archive,destination,top_dir=None):
    actual=validate_archive(archive,top_dir)
    if destination.is_symlink() or (destination.exists() and any(destination.iterdir())):raise ValueError('extraction requires empty directory')
    destination.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for i in z.infolist():
            p=destination/i.filename
            if i.is_dir():p.mkdir(parents=True,exist_ok=True);p.chmod(0o755)
            else:
                p.parent.mkdir(parents=True,exist_ok=True)
                with z.open(i) as src,p.open('xb') as dst:shutil.copyfileobj(src,dst,1024*1024)
                p.chmod(stat.S_IMODE(i.external_attr>>16))
    return destination/actual
if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);g=ap.add_mutually_exclusive_group(required=True);g.add_argument('--tree',type=Path);g.add_argument('--archive',type=Path);a=ap.parse_args()
    print('SOURCE POLICY PASS',sum(1 for _ in source_files(a.tree)) if a.tree else validate_archive(a.archive))
