#!/usr/bin/env python3
"""Build the actual source ZIP, extract safely and verify release policies."""
import importlib.util,json,os,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    spec=importlib.util.spec_from_file_location('packager',ROOT/'tools/package-v71-source.py');p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
    with tempfile.TemporaryDirectory(prefix='mhl-source-smoke-') as d:
        archive=Path(d)/'source.zip';p.create_zip(ROOT,archive,'MyHomeLib-8.0.0');p.verify_archive(archive,'MyHomeLib-8.0.0');result={'status':'PASS','candidateSha':os.environ.get('GITHUB_SHA'),'archiveSha256':p.sha256(archive),'archiveBytes':archive.stat().st_size}
    out=ROOT/'target/source-archive-smoke.json';out.parent.mkdir(exist_ok=True);out.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
if __name__=='__main__':main()
