#!/usr/bin/env python3
import importlib.util,os,stat,tempfile,unittest,zipfile
from pathlib import Path
from source_archive_policy import extract_archive,source_files,validate_archive
spec=importlib.util.spec_from_file_location('packager',Path(__file__).with_name('package-v71-source.py'));pack=importlib.util.module_from_spec(spec);spec.loader.exec_module(pack)
class ArchiveTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name);self.archive=self.root/'source.zip'
    def zip(self,entries):
        with zipfile.ZipFile(self.archive,'w') as z:
            for name,mode in entries:
                i=zipfile.ZipInfo(name);i.create_system=3;i.external_attr=mode<<16;z.writestr(i,b'payload')
        return self.archive
    def test_round_trip_modes_bytes(self):
        source=self.root/'source';source.mkdir();(source/'run.sh').write_text('#!/bin/sh\nexit 0\n');(source/'run.sh').chmod(0o755);(source/'data').write_text('data');pack.create_zip(source,self.archive,'project');out=extract_archive(self.archive,self.root/'out','project');self.assertEqual('data',(out/'data').read_text());self.assertEqual(0o755,stat.S_IMODE((out/'run.sh').stat().st_mode))
    def test_unsafe_paths(self):
        for name in ['../escape','/absolute','project/../escape','project\\escape','project/C:stream','project/a/./b','project/file.']:
            with self.subTest(name=name),self.assertRaises(ValueError):validate_archive(self.zip([(name,stat.S_IFREG|0o644)]))
    def test_case_parent_collisions(self):
        for names in [('project/a','project/A'),('project/a','project/a/b')]:
            with self.subTest(names=names),self.assertRaises(ValueError):validate_archive(self.zip([(n,stat.S_IFREG|0o644) for n in names]))
    def test_links_special_types(self):
        for mode in [stat.S_IFLNK|0o777,stat.S_IFIFO|0o644,0o644,stat.S_IFREG|0o4777]:
            with self.subTest(mode=mode),self.assertRaises(ValueError):validate_archive(self.zip([('project/a',mode)]))
    def test_modes_multiple_roots(self):
        with self.assertRaises(ValueError):validate_archive(self.zip([('project/run.sh',stat.S_IFREG|0o644)]))
        with self.assertRaises(ValueError):validate_archive(self.zip([('a/x',stat.S_IFREG|0o644),('b/x',stat.S_IFREG|0o644)]))
    def test_no_destination_overwrite(self):
        self.zip([('project/data',stat.S_IFREG|0o644)]);out=self.root/'out';out.mkdir();(out/'keep').write_text('original')
        with self.assertRaises(ValueError):extract_archive(self.archive,out)
        self.assertEqual('original',(out/'keep').read_text())
    @unittest.skipIf(os.name=='nt','POSIX modes')
    def test_bad_source_keeps_prior_archive(self):
        source=self.root/'source';source.mkdir();(source/'run.sh').write_text('bad mode');(source/'run.sh').chmod(0o644);self.archive.write_bytes(b'previous')
        with self.assertRaises(ValueError):pack.create_zip(source,self.archive,'project')
        self.assertEqual(b'previous',self.archive.read_bytes())
    @unittest.skipIf(os.name=='nt','POSIX link fixture')
    def test_source_links(self):
        source=self.root/'source';source.mkdir();(source/'link').symlink_to(self.root,target_is_directory=True)
        with self.assertRaises(ValueError):list(source_files(source))
if __name__=='__main__':unittest.main()
