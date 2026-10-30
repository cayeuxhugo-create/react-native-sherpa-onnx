"""Exercise the v3.5.4 PowerShell runner without a phone or private audio."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
collector = '''# adaptive-candidate-3.5.4.0
param($ProjectDirectory,$PackageName,$ProcessingMode,$CaptureRoute,$ExpectedEngineRevision,[switch]$RequireTemporalRescue,$ExpectedAudioSource,$ExpectedCaptureChannels,$ExpectedChannelIndexMask,$TestCase,$DurationSeconds,$DistanceMeters,$VoiceLevel,$NoiseDescription,$DeviceId)
$ErrorActionPreference='Stop'
@{mode=$ProcessingMode;route=$CaptureRoute;version=$ExpectedEngineRevision;requireRescue=[bool]$RequireTemporalRescue;source=$ExpectedAudioSource;channels=$ExpectedCaptureChannels;mask=$ExpectedChannelIndexMask;duration=$DurationSeconds;distance=$DistanceMeters;testCase=$TestCase;device=$DeviceId} | ConvertTo-Json | Set-Content (Join-Path $ProjectDirectory 'arguments.json')
if ($env:RESCUE_FIXTURE -eq 'no-archive') { exit 1 }
$out=Join-Path $ProjectDirectory 'diagnostics-voix'
[void][IO.Directory]::CreateDirectory($out)
[IO.File]::WriteAllBytes((Join-Path $out 'new.zip'),[byte[]]@(1,2,3))
if ($env:RESCUE_FIXTURE -eq 'collector-error') { exit 1 }
'''
splitter = '''param($ArchivePath)
if (!(Test-Path -LiteralPath $ArchivePath)) { throw 'missing fixture archive' }
Set-Content -LiteralPath ($ArchivePath+'.prepared') -Value $ArchivePath
'''
cases = [('ok','3M_BRUIT'),('ok','5M_CALME'),('ok','SILENCE_BRUIT'),
         ('collector-error','3M_BRUIT'),('no-archive','3M_BRUIT'),('old-collector','3M_BRUIT')]
with tempfile.TemporaryDirectory(prefix='guardian-rescue-runner-') as tmp:
    for index, (scenario, test) in enumerate(cases):
        root=Path(tmp)/str(index);tools=root/'tools with spaces';tools.mkdir(parents=True)
        project=root/'application';project.mkdir();(project/'package.json').write_text('{}')
        archives=project/'diagnostics-voix';archives.mkdir();(archives/'old.zip').write_bytes(b'keep')
        shutil.copyfile(repo/'tester-guardian-secours-temporel.ps1',tools/'runner.ps1')
        fixture = collector.replace('adaptive-candidate-3.5.4.0','adaptive-candidate-3.5.3.2') if scenario=='old-collector' else collector
        (tools/'enregistrer-guardian-adaptatif.ps1').write_text(fixture,encoding='utf-8-sig')
        (tools/'preparer-envoi-diagnostic.ps1').write_text(splitter,encoding='utf-8-sig')
        env=dict(os.environ,RESCUE_FIXTURE=scenario)
        for key in ['XDG_CONFIG_HOME','XDG_CACHE_HOME','XDG_DATA_HOME']:
            env[key]=str(root/key);Path(env[key]).mkdir()
        result=subprocess.run([os.environ.get('PWSH','pwsh'),'-NoProfile','-File',str(tools/'runner.ps1'),
            '-ProjectDirectory',str(project),'-PackageName','com.example.test','-Scenario',test,'-DeviceId','fixture:5555'],
            env=env,text=True,capture_output=True)
        assert (result.returncode==0)==(scenario=='ok'),result.stdout+result.stderr
        if scenario!='old-collector':
            args=json.loads((project/'arguments.json').read_text(encoding='utf-8-sig'))
            assert args==dict(mode='ADAPTIVE_CANDIDATE',route='AUTO',version='guardian-voice-v3.5.4-temporal-rescue',
                requireRescue=True,source='1',channels='4',mask='15',duration='85',distance='5' if test=='5M_CALME' else '3',
                testCase='SILENCE' if test=='SILENCE_BRUIT' else 'PHRASES',device='fixture:5555'),args
        assert (archives/'old.zip').read_bytes()==b'keep' and not (archives/'old.zip.prepared').exists()
        assert (archives/'new.zip.prepared').exists()==(scenario in ['ok','collector-error'])
        print('PASS temporal rescue runner',scenario,test,flush=True)
