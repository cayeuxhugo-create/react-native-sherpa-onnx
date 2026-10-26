"""Run the PowerShell collector against a simulated ADB, never a real phone.
Set PWSH to a local pwsh executable. Audio is a synthetic binary fixture.
"""
from pathlib import Path
import os,subprocess,tempfile,json,zipfile
repo=Path(__file__).resolve().parents[3]
source=(repo/'etude-silence/fake_adb.py').read_text()
source=source.replace('guardian-voice-v3.4.3-speech-gain-study','guardian-voice-v3.4.4-continuous-study')
source=source.replace("elif a[:2]==['shell','getprop']", "elif a[:2]==['shell','dumpsys']:print('Synthetic memory report')\nelif a[:2]==['shell','getprop']")
source=source.replace("if event=='WAV_READY':emit(","if event=='WAV_READY':emit('CONTINUOUS_STUDY_READY')\n  if event=='WAV_READY':emit(")
source=source.replace(" if a[-1]=='SIMPLE_RECORD_END':", """ if a[-1]=='SIMPLE_RECORD_END':
  prefix='guardian-voice-1792424800000-NOISY-abc12345'
  for folder in ['', 'segment-1/']:
   for suffix in ['truth.wav','asr.wav','truth.f32','asr.f32','index.tsv']:
    src=f('files/guardian-diagnostics/'+prefix+'-'+suffix)
    dst=f('files/guardian-diagnostics/continuous-study/abc12345/'+folder+prefix+'-'+suffix)
    dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
  if mode=='study-invalid':emit('CONTINUOUS_STUDY_INVALID',reason='QUEUE_OVERFLOW')
  emit('CONTINUOUS_STUDY_END',valid=mode!='study-invalid',inputSamples=32000,outputSamples=31200,incompleteWindows=0)
""")
source=source.replace(" if c[0]=='id':", " if c[0]=='find':print('\\n'.join(str(p.relative_to(r)) for p in f(c[1]).rglob('*') if p.is_file()))\n elif c[0]=='id':")
with tempfile.TemporaryDirectory(prefix='guardian-continuous-collector-') as tmp:
 for mode in ['ok','study-invalid','float-corrupt','float-truncated']:
  d=Path(tmp)/mode;(d/'remote/files').mkdir(parents=True);(d/'bin').mkdir();(d/'app with spaces').mkdir();(d/'app with spaces/package.json').write_text('{}')
  adb=d/'bin/adb';adb.write_text(source);adb.chmod(0o755)
  env=dict(os.environ,PATH=str(d/'bin')+':'+os.environ['PATH'],FAKE_ADB_ROOT=str(d/'remote'),FAKE_MODE=mode,FAKE_FIRERED='5',XDG_CACHE_HOME=str(d/'cache'),XDG_CONFIG_HOME=str(d/'config'),XDG_DATA_HOME=str(d/'data'))
  cmd=[os.environ.get('PWSH','pwsh'),'-NoProfile','-File',str(repo/'enregistrer-guardian-continu.ps1'),'-ProjectDirectory',str(d/'app with spaces'),'-PackageName','com.example.test','-DurationSeconds','1']
  run=subprocess.run(cmd,env=env,input='fixture\n\n\n',text=True,capture_output=True,timeout=35)
  assert (run.returncode==0)==(mode=='ok'),run.stdout+run.stderr
  archives=list((d/'app with spaces/diagnostics-voix').glob('*.zip'));assert len(archives)==1
  with zipfile.ZipFile(archives[0]) as z:
   m=json.loads(z.read('session.json').decode('utf-8-sig'));assert m['completed']==(mode=='ok') and m['controlsRestored']
   if mode=='ok':
    assert len([x for x in z.namelist() if 'continuous-study' in x and x.endswith('.f32')])==4
    assert z.read('meminfo-before.txt') and z.read('meminfo-after.txt')
  print('PASS continuous collector',mode,flush=True)
