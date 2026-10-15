#!/usr/bin/env python3
"""Verify reviewed vendor hashes; inventory PASS is not CVE or license clearance."""
import argparse,hashlib,json
from datetime import date
from pathlib import Path
from source_archive_policy import source_files
ROOT=Path(__file__).resolve().parents[1]
def sha256(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def binaries(root):
    for p in source_files(root):
        with p.open('rb') as f:head=f.read(4)
        if p.suffix.lower() in {'.jar','.exe','.dll','.so','.dylib'} or head[:2]==b'MZ' or head in {b'\x7fELF',b'\xfe\xed\xfa\xce',b'\xce\xfa\xed\xfe',b'\xfe\xed\xfa\xcf',b'\xcf\xfa\xed\xfe',b'\xca\xfe\xba\xbe'}:yield p

def check_manifest(root,manifest):
    if manifest.get('schemaVersion')!=1:raise ValueError('unsupported manifest')
    actual={p.relative_to(root).as_posix():p for p in binaries(root/'tools')};components=manifest['components'];expected={c['path']:c for c in components}
    if len(expected)!=len(components) or expected.keys()!=actual.keys():raise ValueError('binary file set drift')
    for name,c in expected.items():
        if c['sha256']!=sha256(actual[name]) or c['sizeBytes']!=actual[name].stat().st_size:raise ValueError(f'binary hash/size drift: {name}')
        for key in ['name','version','license','licenseEvidence','identityEvidence','metadataStatus','sourceKind']:
            if not c.get(key):raise ValueError(f'missing {key}: {name}')
        date.fromisoformat(c['checkedOn'])
        if c.get('sourceURL') is None and c['metadataStatus']!='unverified-native-build':raise ValueError('unexplained source URL')
        for value in c['licenseEvidence']:
            p=Path(value)
            if p.is_absolute() or '..' in p.parts or not (root/p).is_file():raise ValueError(f'missing/unsafe license evidence: {value}')
    return components

def sbom(components):
    items=[]
    for c in components:
        item={'type':'library' if c['path'].endswith('.jar') else 'file','bom-ref':c['path'],'name':c['name'],'version':c['version'],'hashes':[{'alg':'SHA-256','content':c['sha256']}],'licenses':[{'license':{'name':c['license']}}],'properties':[{'name':'myhomelib:'+k,'value':str(c[k])} for k in ['path','checkedOn','metadataStatus','sourceKind']]}
        if c.get('purl'):item.update(purl=c['purl'],group=c['group'])
        if c.get('sourceURL'):item['externalReferences']=[{'type':'website','url':c['sourceURL']}]
        items.append(item)
    return {'$schema':'http://cyclonedx.org/schema/bom-1.6.schema.json','bomFormat':'CycloneDX','specVersion':'1.6','version':1,'metadata':{'properties':[{'name':'myhomelib:scope','value':'Supplementary vendor files; combine with Maven SBOM and image scan. Native provenance unresolved.'}]},'components':items}

def report_hashes(report):
    if report.get('scanInfo',{}).get('analysisExceptions'):raise ValueError('SCA analysis exceptions')
    hashes=set()
    def visit(v):
        if isinstance(v,dict):
            if isinstance(v.get('sha256'),str):hashes.add(v['sha256'].lower())
            for x in v.values():visit(x)
        elif isinstance(v,list):
            for x in v:visit(x)
    visit(report.get('dependencies',[]));return hashes

def check_sca_coverage(components,report):
    hashes=report_hashes(report);missing=[c['path'] for c in components if c['path'].endswith('.jar') and c['sha256'] not in hashes]
    if missing:raise ValueError(f'SCA omitted vendor JAR hashes: {missing}')
def check_image_coverage(image,report):
    jars=sorted(image.rglob('*.jar'))
    if not jars:raise ValueError('final app-image JARs missing')
    hashes=report_hashes(report);missing=[str(p) for p in jars if sha256(p) not in hashes]
    if missing:raise ValueError(f'SCA omitted image JAR hashes: {missing}')
if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--check',action='store_true');ap.add_argument('--root',type=Path,default=ROOT);ap.add_argument('--sbom',type=Path);ap.add_argument('--sca-report',type=Path);ap.add_argument('--image-root',type=Path);a=ap.parse_args()
    if a.image_root and not a.sca_report:ap.error('--image-root requires --sca-report')
    components=check_manifest(a.root,json.loads((a.root/'security/binary-manifest.json').read_text(encoding='utf-8')))
    if a.sbom:a.sbom.parent.mkdir(parents=True,exist_ok=True);a.sbom.write_text(json.dumps(sbom(components),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    if a.sca_report:
        report=json.loads(a.sca_report.read_text(encoding='utf-8'));check_sca_coverage(components,report)
        if a.image_root:check_image_coverage(a.image_root,report)
        print('SCA JAR HASH COVERAGE PASS; native review separate')
    print(f'VENDOR INVENTORY PASS ({len(components)} binaries; no CVE/license-clearance assertion)')
