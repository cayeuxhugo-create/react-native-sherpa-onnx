import os,subprocess,pathlib,json,zipfile,tempfile,shutil,sys
REPO=pathlib.Path(__file__).resolve().parents[1]
PWSH=shutil.which("pwsh")
if not PWSH:raise SystemExit("PowerShell 7 required (POSIX fake-ADB runner)")
stream='--firered-stream' in sys.argv
firered='--firered' in sys.argv or stream
root=pathlib.Path(tempfile.mkdtemp(prefix='guardian-collector-test-'))
(root/'bin').mkdir()
shutil.copyfile(pathlib.Path(__file__).with_name('fake_adb.py'),root/'bin/adb')
(root/'bin/adb').chmod(0o755)
cases=[('ok','AUTO',True),('stereo','MIC_STEREO_FOCUS',False),('mic','MIC_MONO',False),('voice','VOICE_RECOGNITION_MONO',False),('old','AUTO',True),('corrupt','AUTO',True),('limit','AUTO',False),('route-rejected','MIC_STEREO_FOCUS',True),('ended-then-active','AUTO',False),('restart','AUTO',True),('short-wav','AUTO',True),('duplicate-saved','AUTO',False)]
if firered:cases += [('wrong-engine','AUTO',True),('firered-failed','AUTO',True)]
for mode,route,existing in cases:
 d=root/mode;d.mkdir();remote=d/'remote';(remote/'files').mkdir(parents=True)
 prior={n:b for n,b in [('guardian-record-wav.txt',b'OFF\r\n'),('guardian-voice-profile.txt',b'NOISY\r\n'),('guardian-audio-source.txt',b'MIC\r\n'),('guardian-multimic.txt',b'LEFT\r\n')]} if existing else {}
 for n,b in prior.items():(remote/'files'/n).write_bytes(b)
 project=d/'Project with spaces';project.mkdir();(project/'package.json').write_text('{}')
 env=dict(os.environ,PATH=str(root/'bin')+':'+os.environ['PATH'],FAKE_ADB_ROOT=str(remote),FAKE_MODE=mode,FAKE_FIRERED='2' if stream else '1' if firered else '0',XDG_CACHE_HOME=str(root/'cache'),XDG_CONFIG_HOME=str(root/'config'),XDG_DATA_HOME=str(root/'data'))
 r=subprocess.run([PWSH,'-NoProfile','-File',str(REPO/('enregistrer-firered-stream.ps1' if stream else 'enregistrer-firered.ps1' if firered else 'enregistrer-guardian.ps1')),'-ProjectDirectory',str(project),'-PackageName','com.example.test','-CaptureRoute',route,'-DurationSeconds','1'],input='\n\n\n',text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=env,timeout=30)
 (d/'result.log').write_text(r.stdout);success=mode in ['ok','stereo','mic','voice','ended-then-active'];assert (r.returncode==0)==success,r.stdout
 zips=list(project.rglob('*.zip'));assert len(zips)==1,r.stdout
 with zipfile.ZipFile(zips[0]) as z:
  meta=json.loads(z.read('session.json').decode('utf-8-sig'));assert meta['completed']==success;assert meta['controlsRestored'];
  if success:assert meta['session']=='abc12345'
  assert meta['captureRouteRequested']==route;assert meta['collectorVersion']==('firered-stream-3.4' if stream else 'firered-3.3' if firered else 'simple-3.1');assert 'audio-routing.log' in z.namelist()
  if success or mode=='limit':
   wav=[n for n in z.namelist() if n.endswith('.wav')];assert len(wav)==5
   for n in wav:assert z.read(n)==(remote/'files/guardian-diagnostics'/pathlib.Path(n).name).read_bytes()
 for n in ['guardian-record-wav.txt','guardian-voice-profile.txt','guardian-audio-source.txt','guardian-multimic.txt']:
  p=remote/'files'/n;assert (p.read_bytes() if p.exists() else None)==prior.get(n),r.stdout
 assert not list((remote/'data/local/tmp').glob('*.txt'))
 print('PASS',mode,route,'ZIP, five-stage integrity, route validation and exact restoration',flush=True)

shutil.rmtree(root)
