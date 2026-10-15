#!/usr/bin/env python3
import hashlib,importlib.util,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('inventory',Path(__file__).with_name('vendor-binary-inventory.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class VendorTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name);(self.root/'tools').mkdir();(self.root/'tools/a.jar').write_bytes(b'test jar');(self.root/'license.txt').write_text('test license')
        self.c={'path':'tools/a.jar','sha256':hashlib.sha256(b'test jar').hexdigest(),'sizeBytes':8,'name':'a','version':'1','sourceURL':'https://example.org','sourceKind':'test','license':'test','licenseEvidence':['license.txt'],'identityEvidence':['test'],'metadataStatus':'test','checkedOn':'2026-10-13'};self.manifest={'schemaVersion':1,'components':[self.c]}
    def test_inventory_sbom(self):
        self.assertEqual([self.c],m.check_manifest(self.root,self.manifest));self.assertEqual(self.c['sha256'],m.sbom([self.c])['components'][0]['hashes'][0]['content'])
    def test_changed_missing_unknown_binary(self):
        (self.root/'tools/a.jar').write_bytes(b'changed!')
        with self.assertRaises(ValueError):m.check_manifest(self.root,self.manifest)
        (self.root/'tools/a.jar').unlink()
        with self.assertRaises(ValueError):m.check_manifest(self.root,self.manifest)
        (self.root/'tools/a.jar').write_bytes(b'test jar');(self.root/'tools/renamed-native').write_bytes(b'\x7fELFbytes')
        with self.assertRaises(ValueError):m.check_manifest(self.root,self.manifest)
    def test_missing_license(self):
        (self.root/'license.txt').unlink()
        with self.assertRaises(ValueError):m.check_manifest(self.root,self.manifest)
    def test_exact_sca_hashes_and_errors(self):
        with self.assertRaises(ValueError):m.check_sca_coverage([self.c],{'dependencies':[]})
        m.check_sca_coverage([self.c],{'dependencies':[{'relatedDependencies':[{'sha256':self.c['sha256']}]}]})
        with self.assertRaises(ValueError):m.check_sca_coverage([self.c],{'scanInfo':{'analysisExceptions':['failed']},'dependencies':[{'sha256':self.c['sha256']}]})
    def test_image_missing_or_unscanned(self):
        with self.assertRaises(ValueError):m.check_image_coverage(self.root/'missing',{'dependencies':[]})
        with self.assertRaises(ValueError):m.check_image_coverage(self.root/'tools',{'dependencies':[]})
        m.check_image_coverage(self.root/'tools',{'dependencies':[{'sha256':self.c['sha256']}]})
if __name__=='__main__':unittest.main()
