import os,subprocess,pathlib,json,zipfile,tempfile,shutil,sys
REPO=pathlib.Path(__file__).resolve().parents[1]
PWSH=shutil.which("pwsh")
if not PWSH:raise SystemExit("PowerShell 7 required (POSIX fake-ADB runner)")
speech_gain='--speech-gain' in sys.argv
floating='--float' in sys.argv or speech_gain
comparison='--gtcrn-comparison' in sys.argv or floating
stream='--firered-stream' in sys.argv or comparison
firered='--firered' in sys.argv or stream
root=pathlib.Path(tempfile.mkdtemp(prefix='guardian-collector-test-'))
(root/'bin').mkdir()
shutil.copyfile(pathlib.Path(__file__).with_name('fake_adb.py'),root/'bin/adb')
(root/'bin/adb').chmod(0o755)
cases=[('ok','AUTO',True),('stereo','MIC_STEREO_FOCUS',False),('mic','MIC_MONO',False),('voice','VOICE_RECOGNITION_MONO',False),('old','AUTO',True),('corrupt','AUTO',True),('limit','AUTO',False),('route-rejected','MIC_STEREO_FOCUS',True),('ended-then-active','AUTO',False),('restart','AUTO',True),('short-wav','AUTO',True),('duplicate-saved','AUTO',False)]
if speech_gain:cases += [('candidate','AUTO',True),('processing-mode-not-applied','AUTO',True),('stereo-missing','MIC_STEREO_FOCUS',False)]
if floating:cases += [('float-truncated','AUTO',False),('float-corrupt','AUTO',True),('export-error','AUTO',False)]
if comparison:cases += [('shadow-unavailable','AUTO',False)]
if firered:cases += [('wrong-engine','AUTO',True),('firered-failed','AUTO',True)]
for mode,route,existing in cases:
 d=root/mode;d.mkdir();remote=d/'remote';(remote/'files').mkdir(parents=True)
 prior={n:b for n,b in [('guardian-record-wav.txt',b'OFF\r\n'),('guardian-voice-profile.txt',b'NOISY\r\n'),('guardian-audio-source.txt',b'MIC\r\n'),('guardian-multimic.txt',b'LEFT\r\n')]} if existing else {}
 if speech_gain and existing:prior['guardian-voice-processing.txt']=b'ORIGINAL\r\n'
 for n,b in prior.items():(remote/'files'/n).write_bytes(b)
 project=d/'Project with spaces';project.mkdir();(project/'package.json').write_text('{}')
 env=dict(os.environ,PATH=str(root/'bin')+':'+os.environ['PATH'],FAKE_ADB_ROOT=str(remote),FAKE_MODE=mode,FAKE_FIRERED='5' if speech_gain else '4' if floating else '3' if comparison else '2' if stream else '1' if firered else '0',XDG_CACHE_HOME=str(root/'cache'),XDG_CONFIG_HOME=str(root/'config'),XDG_DATA_HOME=str(root/'data'))
 r=subprocess.run([PWSH,'-NoProfile','-File',str(REPO/('enregistrer-guardian-etude.ps1' if speech_gain else 'enregistrer-guardian-float.ps1' if floating else 'enregistrer-gtcrn-comparison.ps1' if comparison else 'enregistrer-firered-stream.ps1' if stream else 'enregistrer-firered.ps1' if firered else 'enregistrer-guardian.ps1')),'-ProjectDirectory',str(project),'-PackageName','com.example.test','-CaptureRoute',route,'-DurationSeconds','1']+(['-Profile','NOISY'] if comparison else [])+(['-ProcessingMode','CANDIDATE_GAIN2'] if speech_gain and mode=='candidate' else []),input='\n\n\n',text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=env,timeout=30)
 (d/'result.log').write_text(r.stdout);success=mode in ['ok','stereo','mic','voice','ended-then-active','candidate'];assert (r.returncode==0)==success,r.stdout
 zips=list(project.rglob('*.zip'));assert len(zips)==1,r.stdout
 with zipfile.ZipFile(zips[0]) as z:
  meta=json.loads(z.read('session.json').decode('utf-8-sig'));assert meta['completed']==success;assert meta['controlsRestored'];
  if success:assert meta['session']=='abc12345'
  assert meta['captureRouteRequested']==route;assert meta['collectorVersion']==('speech-gain-study-3.4.3' if speech_gain else 'float-diagnostics-3.4.2' if floating else 'gtcrn-comparison-3.4.1' if comparison else 'firered-stream-3.4' if stream else 'firered-3.3' if firered else 'simple-3.1');assert 'audio-routing.log' in z.namelist()
  if floating:
   assert 'test-plan.json' in z.namelist() and 'test-markers.json' in z.namelist()
   if success:
    markers=json.loads(z.read('test-markers.json').decode('utf-8-sig'));assert [m['label'] for m in markers]==['BEGIN','END']
    floats=[n for n in z.namelist() if n.endswith('.f32')];assert len(floats)==7
    for n in floats:assert z.read(n)==(remote/'files/guardian-diagnostics'/('gtcrn-comparison' if n.startswith('gtcrn-comparison/') else '')/pathlib.Path(n).name).read_bytes()
  if success or mode=='limit':
   wav=[n for n in z.namelist() if n.endswith('.wav')];assert len(wav)==(8 if speech_gain and route=='MIC_STEREO_FOCUS' and success else 7 if comparison and mode!='limit' else 5)
   for n in wav:
    source=(remote/'storage/emulated/0/Android/data/com.example.test/files/guardian-diagnostics'/pathlib.Path(n).name) if n.startswith('multimic/') else (remote/'files/guardian-diagnostics'/('gtcrn-comparison' if n.startswith('gtcrn-comparison/') else '')/pathlib.Path(n).name)
    assert z.read(n)==source.read_bytes()
 for n in ['guardian-record-wav.txt','guardian-voice-profile.txt','guardian-audio-source.txt','guardian-multimic.txt']+(['guardian-voice-processing.txt'] if speech_gain else []):
  p=remote/'files'/n;assert (p.read_bytes() if p.exists() else None)==prior.get(n),r.stdout
 assert not list((remote/'data/local/tmp').glob('*.txt'))
 print('PASS',mode,route,'ZIP, five-stage integrity, route validation and exact restoration',flush=True)

shutil.rmtree(root)
