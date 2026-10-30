"""Test the PowerShell route runner without a phone or private audio."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
collector = '''# adaptive-candidate-3.5.3.2
param($ProjectDirectory,$PackageName,$ProcessingMode,$CaptureRoute,$ExpectedAudioSource,$ExpectedCaptureChannels,$ExpectedChannelIndexMask,$TestCase,$DurationSeconds,$DistanceMeters,$VoiceLevel,$NoiseDescription)
$ErrorActionPreference='Stop'
@{mode=$ProcessingMode;route=$CaptureRoute;source=$ExpectedAudioSource;channels=$ExpectedCaptureChannels;mask=$ExpectedChannelIndexMask;duration=$DurationSeconds;distance=$DistanceMeters} | ConvertTo-Json | Set-Content (Join-Path $ProjectDirectory 'arguments.json')
if ($env:ROUTE_FIXTURE -eq 'no-archive') { exit 1 }
$out=Join-Path $ProjectDirectory 'diagnostics-voix'
[void][IO.Directory]::CreateDirectory($out)
[IO.File]::WriteAllBytes((Join-Path $out 'new.zip'),[byte[]]@(1,2,3))
if ($env:ROUTE_FIXTURE -eq 'collector-error') { exit 1 }
'''
splitter = '''param($ArchivePath)
if (!(Test-Path -LiteralPath $ArchivePath)) { throw 'missing fixture archive' }
Set-Content -LiteralPath ($ArchivePath+'.prepared') -Value $ArchivePath
'''
with tempfile.TemporaryDirectory(prefix='guardian-route-runner-') as tmp:
    for scenario in ['ok','collector-error','no-archive']:
        root=Path(tmp)/scenario;tools=root/'tools with spaces';tools.mkdir(parents=True)
        project=root/'application';project.mkdir();(project/'package.json').write_text('{}')
        archives=project/'diagnostics-voix';archives.mkdir();(archives/'old.zip').write_bytes(b'keep')
        shutil.copyfile(repo/'tester-guardian-route-initiale.ps1',tools/'runner.ps1')
        (tools/'enregistrer-guardian-adaptatif.ps1').write_text(collector,encoding='utf-8-sig')
        (tools/'preparer-envoi-diagnostic.ps1').write_text(splitter,encoding='utf-8-sig')
        env=dict(os.environ,ROUTE_FIXTURE=scenario)
        for key in ['XDG_CONFIG_HOME','XDG_CACHE_HOME','XDG_DATA_HOME']:
            env[key]=str(root/key);Path(env[key]).mkdir()
        result=subprocess.run([os.environ.get('PWSH','pwsh'),'-NoProfile','-File',str(tools/'runner.ps1'),
            '-ProjectDirectory',str(project),'-PackageName','com.example.test'],env=env,text=True,capture_output=True)
        assert (result.returncode==0)==(scenario=='ok'),result.stdout+result.stderr
        args=json.loads((project/'arguments.json').read_text(encoding='utf-8-sig'))
        assert args==dict(mode='ADAPTIVE_CANDIDATE',route='AUTO',source='1',channels='4',mask='15',duration='85',distance='3')
        assert (archives/'old.zip').read_bytes()==b'keep' and not (archives/'old.zip.prepared').exists()
        assert (archives/'new.zip.prepared').exists()==(scenario!='no-archive')
        print('PASS initial route wrapper',scenario,flush=True)
