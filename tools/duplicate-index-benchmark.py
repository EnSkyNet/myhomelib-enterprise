from pathlib import Path
import runpy, sqlite3, tempfile, time, statistics, json
ROOT=Path(__file__).resolve().parents[1]
S=runpy.run_path(str(ROOT/'tools/stage24-performance-baseline.py'))
configure_fixture=S['configure_fixture']; migrate=S['migrate']; synthesize=S['synthesize']
QUERY='''SELECT COALESCE(SUM(cnt - 1), 0) FROM (SELECT COUNT(*) AS cnt FROM books WHERE COALESCE(deleted,0)=0 AND TRIM(COALESCE(lib_id,'')) <> '' GROUP BY lib_id, COALESCE(collection_root,''), COALESCE(folder,''), COALESCE(file_name,''), COALESCE(archive_entry,'') HAVING COUNT(*) > 1) duplicate_groups'''
IDX='CREATE INDEX idx_books_physical_identity_probe ON books(lib_id, collection_root, folder, file_name, archive_entry)'
INSERT='''INSERT INTO books(id,title,file_name,folder,language,file_size,keywords,annotation,rate,progress,update_date,deleted,local,collection_root,format,author_sort,publisher,year,lib_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)'''
def runq(c,n=7):
  ts=[]
  for _ in range(n):
    t=time.perf_counter(); c.execute(QUERY).fetchone(); ts.append((time.perf_counter()-t)*1000)
  return {'median_ms':statistics.median(ts),'p95_ms':sorted(ts)[max(0,int(len(ts)*.95)-1)],'runs_ms':ts}
def insertprobe(c,size,rows=12000):
  base=99_000_000
  vals=[]
  for k in range(rows):
    i=base+k
    vals.append((f'p{i}',f'Probe {i}',f'p{i}.fb2','/probe','uk',100,'','',0,0,'2026-08-30',0,0,'/probe','FB2','A','P',2026,f'P-{i}'))
  c.execute('BEGIN'); t=time.perf_counter(); c.executemany(INSERT,vals); ms=(time.perf_counter()-t)*1000; c.rollback(); return {'ms':ms,'rows_per_sec':rows/(ms/1000)}
rows=[]
for size in (100_000,500_000):
  print('fixture',size,flush=True)
  with tempfile.TemporaryDirectory() as td:
    db=Path(td)/'d.db'; c=sqlite3.connect(db); configure_fixture(c); migrate(c); synthesize(c,size); c.commit()
    baseplan=[r[3] for r in c.execute('EXPLAIN QUERY PLAN '+QUERY)]
    baseq=runq(c); basei=insertprobe(c,size)
    t=time.perf_counter(); c.execute(IDX); c.commit(); create_ms=(time.perf_counter()-t)*1000
    idxplan=[r[3] for r in c.execute('EXPLAIN QUERY PLAN '+QUERY)]
    idxq=runq(c); idxi=insertprobe(c,size)
    rows.append({'books':size,'without_index':{'plan':baseplan,'query':baseq,'insert':basei},'with_index':{'plan':idxplan,'query':idxq,'insert':idxi,'create_ms':create_ms}})
    print(size, 'q',baseq['median_ms'],idxq['median_ms'],'insert',basei['rows_per_sec'],idxi['rows_per_sec'],flush=True)
    c.close()
out=ROOT/'docs/release/PERFORMANCE-v7.1-DUPLICATE-INDEX-PROBE.json'; out.parent.mkdir(parents=True, exist_ok=True); out.write_text(json.dumps({'date':'2026-08-30','index':'idx_books_physical_identity_probe','definition':IDX,'results':rows},indent=2),encoding='utf-8'); print(out)
