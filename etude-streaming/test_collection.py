import pathlib,os,subprocess,json,zipfile,shutil,tempfile
REPO=pathlib.Path(__file__).resolve().parents[1]
PWSH=shutil.which("pwsh")
if not PWSH:raise SystemExit("PowerShell 7 (pwsh) required; fake ADB test runs on POSIX")
_test_temp=tempfile.TemporaryDirectory(prefix="guardian-study-test-")
root=pathlib.Path(_test_temp.name);root.mkdir(exist_ok=True)
for mode in ['ok','recover','no-start','too-short']:
 d=root/mode;d.mkdir(exist_ok=True);project=d/'project with spaces';project.mkdir(exist_ok=True);(project/'package.json').write_text('{}');bin=d/'bin';bin.mkdir(exist_ok=True);adb=bin/'adb';adb.write_text('#!/bin/sh\nexit 0\n');adb.chmod(0o755)
 shutil.copy(REPO/'test-silence-guardian.ps1',d)
 (d/'enregistrer-guardian.ps1').write_text('''param($ProjectDirectory,$PackageName,$Profile,$DurationSeconds,$DeviceId)
 $p=Join-Path $ProjectDirectory ('diagnostics-voix/Enregistrement-'+[Guid]::NewGuid().ToString('N'))
 [void][IO.Directory]::CreateDirectory($p)
 $m=@{controlsRestored=$true;recordStartUtc='2026-10-20T00:00:00Z';recordEndUtc=if($env:STUDY_MODE -eq 'too-short'){'2026-10-20T00:00:10Z'}else{'2026-10-20T00:01:40Z'};completed=($env:STUDY_MODE -eq 'ok');audioPrefix=if($env:STUDY_MODE -eq 'no-start'){''}else{'guardian-prefix'}}
 $m|ConvertTo-Json|Set-Content (Join-Path $p 'session.json')
 'test'|Set-Content (Join-Path $p 'guardian.log')
 if(-not $m.completed){throw 'Simulated absent WAV_SAVED'}
 ''')
 (d/'recuperer-guardian-wav.ps1').write_text('''param($SessionDirectory,$DeviceId)
 $p=Join-Path $SessionDirectory 'wav-recuperes-test';[void][IO.Directory]::CreateDirectory($p)
 @{completed=$true}|ConvertTo-Json|Set-Content (Join-Path $p 'recuperation.json')
 ''')
 env=dict(os.environ,PATH=str(bin)+':'+os.environ['PATH'],STUDY_MODE=mode,XDG_CACHE_HOME=str(root/'cache'),XDG_CONFIG_HOME=str(root/'config'),XDG_DATA_HOME=str(root/'data'))
 r=subprocess.run([PWSH,'-NoProfile','-File',str(d/'test-silence-guardian.ps1'),'-ProjectDirectory',str(project),'-PackageName','com.example.test'],env=env,capture_output=True,text=True,timeout=30)
 (d/'output.log').write_text(r.stdout+r.stderr)
 assert (r.returncode==0)==(mode not in ['no-start','too-short']),r.stdout+r.stderr
 z=next((project/'diagnostics-voix').glob('Etude-*.zip'))
 with zipfile.ZipFile(z) as archive:
  meta=json.loads(archive.read('etude.json').decode('utf-8-sig'))
  assert meta['completed']==(mode not in ['no-start','too-short'])
  if mode not in ['no-start','too-short']:
   assert len(meta['blocks'])==6
   assert sum(x['requestedSeconds'] for x in meta['blocks'])==600
   assert [x['condition'] for x in meta['blocks']]==['SILENCE']*3+['BRUIT_SANS_VOIX']*3
   assert all(x['recovered']==(mode=='recover') for x in meta['blocks'])
   assert len([n for n in archive.namelist() if n.endswith('session.json')])==6
 print('PASS',mode,flush=True)

_test_temp.cleanup()
