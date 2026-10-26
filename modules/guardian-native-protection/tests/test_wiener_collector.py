"""Run the Wiener PowerShell collector with synthetic ADB and binary audio.

Set PWSH to a local pwsh executable. This never contacts a real phone.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile

repo = Path(__file__).resolve().parents[3]
source = (repo / 'etude-silence/fake_adb.py').read_text()


def replace(old, new):
    global source
    assert old in source, f'Fixture changed: {old!r}'
    source = source.replace(old, new)


replace('guardian-voice-v3.4.3-speech-gain-study', 'guardian-voice-v3.4.6-wiener-study')
replace("elif a[:2]==['shell','getprop']", "elif a[:2]==['shell','dumpsys']:print('Synthetic memory report')\nelif a[:2]==['shell','getprop']")
replace("if event=='WAV_READY':emit(", "if event=='WAV_READY':emit('CONTINUOUS_STUDY_READY',model='GTCRN' if mode=='wrong-study-model' else 'WIENER_DD_ADAPTIVE',role='SHADOW_NO_ALERT')\n  if event=='WAV_READY':emit(")
# The Wiener worker does not start or export GTCRN comparison diagnostics.
replace("if os.environ.get('FAKE_FIRERED') in ['3','4','5']:", "if os.environ.get('FAKE_FIRERED') in ['3','4','5'] and f('files/guardian-voice-processing.txt').read_text().strip()!='WIENER_STUDY':")
replace('gtcrnEnabled=True', 'gtcrnEnabled=False')
replace(" if a[-1]=='SIMPLE_RECORD_END':", """ if a[-1]=='SIMPLE_RECORD_END':
  profile=f('files/guardian-voice-profile.txt').read_text().strip()
  prefix='guardian-voice-1792424800000-'+profile+'-abc12345'
  for folder in ['', 'segment-1/']:
   for suffix in ['truth.wav','asr.wav','truth.f32','asr.f32','index.tsv']:
    src=f('files/guardian-diagnostics/'+prefix+'-'+suffix)
    dst=f('files/guardian-diagnostics/continuous-study/abc12345/'+folder+prefix+'-'+suffix)
    dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
  bypass=mode=='contaminated-bypass'
  emit('CONTINUOUS_STUDY_WINDOW',segment=1,samples=32000,startSample=0,endSample=32000,originalAccepted=True,enhancedAccepted=True,calibrationReady=not bypass,calibrationValid=not bypass,startupBypass=bypass,calibrationReason='CONTAMINATED' if bypass else 'READY',role='SHADOW_NO_ALERT')
  emit('CONTINUOUS_STUDY_ASR_OFFER',segment=1,accepted=True)
  if mode!='asr-pending':emit('STUDY_QWEN_RESULT',segment=1,lane='WIENER_DD_ADAPTIVE',eligible=True,success=True,text='fixture',role='SHADOW_NO_ALERT')
  emit('STUDY_QWEN_RESULT',segment=9,lane='GTCRN_CONTINUOUS',eligible=True,success=True,text='unrelated',role='SHADOW_NO_ALERT')
  if mode=='study-invalid':emit('CONTINUOUS_STUDY_INVALID',reason='QUEUE_OVERFLOW')
  if mode=='study-window-skipped':emit('CONTINUOUS_STUDY_WINDOW_SKIPPED',segment=2,reason='EVICTED')
  emit('CONTINUOUS_STUDY_END',valid=mode!='study-invalid',inputSamples=32000,outputSamples=32000,incompleteWindows=1 if mode=='study-window-incomplete' else 0)
  for channel in range(3):
   if channel==2 and mode=='adaptive-missing-channel':continue
   for suffix in ['raw.wav','raw.f32','index.tsv']:
    src=f('files/guardian-diagnostics/'+prefix+'-'+suffix)
    dst=f('files/guardian-diagnostics/adaptive-channels/abc12345/channel-'+str(channel)+'/'+prefix+'-'+suffix)
    dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
  f('files/guardian-diagnostics/adaptive-channels/abc12345/selection.tsv').write_text('sequence\\tsamples\\tselected\\n1\\t800\\t0\\n')
  with f('events').open('a') as h:h.write('10-26 18:00:00.000 123 123 I GuardianAudioEngine: ADAPTIVE_CHANNELS_WAV_SAVED path=fixture frames=40 dropped='+('1' if mode=='adaptive-dropped' else '0')+' limited=false\\n')
""")
replace("   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')", "   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')\n   with f('events').open('a') as h:h.write('10-26 18:00:00.000 123 123 I GuardianAudioEngine: ADAPTIVE_CHANNELS_READY channels=3 enabled=true indexMask=7\\n')")
replace(" if c[0]=='id':", " if c[0]=='find':print('\\n'.join(str(p.relative_to(r)) for p in f(c[1]).rglob('*') if p.is_file()))\n elif c[0]=='id':")
replace(' sys.stdout.buffer.write(b)', " if (mode=='study-corrupt' and '/continuous-study/' in a[-1] and a[-1].endswith('asr.f32')) or (mode=='channel-trace-corrupt' and a[-1].endswith('/selection.tsv')):b=b[:-1]+bytes([b[-1]^255])\n sys.stdout.buffer.write(b)")

with tempfile.TemporaryDirectory(prefix='guardian-wiener-collector-') as tmp:
    for mode in ['ok', 'contaminated-bypass', 'asr-pending', 'wrong-study-model', 'study-invalid',
                 'study-window-incomplete', 'study-window-skipped',
                 'study-corrupt', 'channel-trace-corrupt', 'adaptive-dropped',
                 'adaptive-missing-channel', 'float-corrupt']:
        d = Path(tmp) / mode
        (d / 'remote/files').mkdir(parents=True)
        (d / 'bin').mkdir()
        project = d / 'app with spaces'
        project.mkdir()
        (project / 'package.json').write_text('{}')
        adb = d / 'bin/adb'
        adb.write_text(source)
        adb.chmod(0o755)
        env = dict(os.environ, PATH=str(d / 'bin') + ':' + os.environ['PATH'],
                   FAKE_ADB_ROOT=str(d / 'remote'), FAKE_MODE=mode, FAKE_FIRERED='5',
                   XDG_CACHE_HOME=str(d / 'cache'), XDG_CONFIG_HOME=str(d / 'config'),
                   XDG_DATA_HOME=str(d / 'data'))
        cmd = [os.environ.get('PWSH', 'pwsh'), '-NoProfile', '-File',
               str(repo / 'enregistrer-guardian-wiener.ps1'), '-ProjectDirectory',
               str(project), '-PackageName', 'com.example.test', '-DurationSeconds', '1']
        if mode == 'contaminated-bypass':
            cmd += ['-Profile', 'NOISY']
        run = subprocess.run(cmd, env=env, input='fixture\n\n\n', text=True,
                             capture_output=True, timeout=35)
        expected_ok = mode in ['ok', 'contaminated-bypass', 'asr-pending']
        assert (run.returncode == 0) == expected_ok, run.stdout + run.stderr
        archives = list((project / 'diagnostics-voix').glob('*.zip'))
        assert len(archives) == 1
        with zipfile.ZipFile(archives[0]) as z:
            metadata = json.loads(z.read('session.json').decode('utf-8-sig'))
            assert metadata['completed'] == expected_ok and metadata['controlsRestored']
            assert metadata['collectorVersion'] == 'wiener-study-3.4.6'
            assert metadata['captureRouteRequested'] == 'AUTO'
            assert metadata['processingModeRequested'] == 'WIENER_STUDY'
            if expected_ok:
                assert metadata['profile'] == ('NOISY' if mode == 'contaminated-bypass' else 'DISTANT')
                assert metadata['continuousStudy']['valid']
                assert metadata['continuousStudyReady']['model'] == 'WIENER_DD_ADAPTIVE'
                assert metadata['wienerStudy']['mainQwenInput'] == 'ORIGINAL'
                assert not metadata['wienerStudy']['gtcrnEnabled']
                assert metadata['continuousStudyWindowsComplete']
                assert metadata['continuousStudyWindowsSkipped'] == []
                assert metadata['diagnosticAsrOffered'] == 1
                assert metadata['diagnosticAsrResults'] == (0 if mode == 'asr-pending' else 1)
                assert metadata['diagnosticAsrComplete'] == (mode != 'asr-pending')
                if mode == 'asr-pending':
                    assert "ne pas les compter comme du silence" in run.stdout
                assert metadata['actualCaptureChannels'] == 3
                assert metadata['adaptiveChannelSelection'] and metadata['channelTraceFrames'] == 1
                assert len([x for x in z.namelist() if x.startswith('continuous-study/') and x.endswith('.f32')]) == 4
                assert len([x for x in z.namelist() if x.startswith('adaptive-channels/') and x.endswith('.f32')]) == 3
                assert len([x for x in z.namelist() if x.startswith('wav/') and x.endswith('.wav')]) == 5
                assert not any(x.startswith('gtcrn-comparison/') for x in z.namelist())
                assert z.read('meminfo-before.txt') and z.read('meminfo-after.txt')
                manifest = json.loads(z.read('wav-manifest.json').decode('utf-8-sig'))
                for record in manifest:
                    name = record['file'] if '/' in record['file'] else 'wav/' + record['file']
                    assert hashlib.sha256(z.read(name)).hexdigest() == record['sha256']
                if mode == 'contaminated-bypass':
                    assert metadata['wienerStudy']['startupBypassWindows'] == 1
                    assert metadata['wienerStudy']['calibrationReadyWindows'] == 0
                    assert metadata['wienerStudy']['calibrationValidWindows'] == 0
                    assert metadata['continuousStudyWindows'][0]['calibrationReason'] == 'CONTAMINATED'
                else:
                    assert metadata['wienerStudy']['startupBypassWindows'] == 0
                    assert metadata['wienerStudy']['calibrationValidWindows'] == 1
            elif mode == 'wrong-study-model':
                assert 'WIENER_DD_ADAPTIVE' in metadata['error']
            elif mode in ['study-window-incomplete', 'study-window-skipped']:
                assert 'Fenetres' in metadata['error']
                assert not metadata['continuousStudyWindowsComplete']
                assert metadata['continuousStudy']['valid']
                assert metadata['diagnosticAsrComplete']
                assert metadata['continuousStudy']['incompleteWindows'] == (1 if mode == 'study-window-incomplete' else 0)
                assert len(metadata['continuousStudyWindowsSkipped']) == (1 if mode == 'study-window-skipped' else 0)
                assert len([x for x in z.namelist() if x.startswith('continuous-study/') and x.endswith('.f32')]) == 4
            elif mode in ['study-corrupt', 'channel-trace-corrupt', 'float-corrupt']:
                assert 'Copie incorrecte' in metadata['error']
        assert not list((d / 'remote/files').glob('guardian-*.txt'))
        print('PASS Wiener collector', mode, flush=True)
