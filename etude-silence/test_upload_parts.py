"""Verify diagnostic ZIP partitioning without losing source bytes. Requires pwsh."""
from pathlib import Path
import zipfile,os,subprocess,hashlib,tempfile,shutil
r=Path(tempfile.mkdtemp(prefix='guardian-upload-test-'));src=r/'Diagnostic avec espaces.zip';expected={}
with zipfile.ZipFile(src,'w',zipfile.ZIP_STORED) as z:
 for name in ['session.json','wav/original.f32','gtcrn-comparison/asr.f32','wav/raw.f32']:
  data=b'{"completed":true}' if name.endswith('.json') else os.urandom(12*1024*1024)
  z.writestr(name,data);expected[name]=hashlib.sha256(data).hexdigest()
original=hashlib.sha256(src.read_bytes()).hexdigest();env=dict(os.environ,XDG_CACHE_HOME=str(r/'cache'),XDG_CONFIG_HOME=str(r/'config'),XDG_DATA_HOME=str(r/'data'))
a=[shutil.which('pwsh') or 'pwsh','-NoProfile','-File',str(Path(__file__).resolve().parents[1]/'preparer-envoi-diagnostic.ps1'),'-ArchivePath',str(src)]
p=subprocess.run(a,env=env,capture_output=True,text=True);assert p.returncode==0,p.stdout+p.stderr
folders=list(r.glob('*-envoi-*'));assert len(folders)==1;actual={};parts=list(folders[0].glob('*.zip'));assert len(parts)>=2
for part in parts:
 assert part.stat().st_size<32*1024*1024
 with zipfile.ZipFile(part) as z:
  for name in z.namelist():assert name not in actual;actual[name]=hashlib.sha256(z.read(name)).hexdigest()
assert expected==actual and original==hashlib.sha256(src.read_bytes()).hexdigest();print('PASS >36 MiB source, paths with spaces, all entries byte-identical exactly once, every part <32 MiB, source unchanged')
large=r/'oversized.zip'
with zipfile.ZipFile(large,'w',zipfile.ZIP_DEFLATED) as z:z.writestr('oversized.f32',b'0'*(25*1024*1024))
p=subprocess.run(a[:-1]+[str(large)],env=env,capture_output=True,text=True);assert p.returncode!=0 and not list(r.glob('oversized-envoi-*'));print('PASS oversized single entry rejected before creating output')

shutil.rmtree(r)
