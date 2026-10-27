"""Exercise the v3.4.7 collector with synthetic ADB, never a real phone.

Set PWSH to a local PowerShell. An annotated-test fixture copy lowers only the
75-second annotation guard to one second; distributed collection keeps the guard.
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


replace('guardian-voice-v3.4.3-speech-gain-study', 'guardian-voice-v3.4.7-channel-coherence')
replace("elif a[:2]==['shell','getprop']", "elif a[:2]==['shell','dumpsys']:print('Synthetic memory report')\nelif a[:2]==['shell','getprop']")
replace("if event=='WAV_READY':emit(", "if event=='WAV_READY':emit('CONTINUOUS_STUDY_READY',model='GTCRN' if mode=='wrong-study-model' else 'WIENER_DD_ADAPTIVE',role='SHADOW_NO_ALERT',processingScope='MIXED_MONO' if mode=='wrong-processing-scope' else 'PER_CHANNEL_COHERENT',alignment='WIENER_PER_CHANNEL_FINALIZED_SAMPLE_CLOCK',maxChannels=30)\n  if event=='WAV_READY':emit(")
replace("if os.environ.get('FAKE_FIRERED') in ['3','4','5']:", "if os.environ.get('FAKE_FIRERED') in ['3','4','5'] and f('files/guardian-voice-processing.txt').read_text().strip()!='WIENER_STUDY':")
replace('gtcrnEnabled=True', 'gtcrnEnabled=False')
replace(" if a[-1]=='SIMPLE_RECORD_END':", """ if a[-1]=='SIMPLE_RECORD_END':
  profile=f('files/guardian-voice-profile.txt').read_text().strip()
  prefix='guardian-voice-1792424800000-'+profile+'-abc12345'
  root='files/guardian-diagnostics/continuous-study/abc12345/'
  channels=[-1] if mode=='mono' else [0,1,2]
  candidate=mode not in ['words-no-candidate','silence-no-candidate']
  bypass=mode in ['phrases-no-calibration','contaminated-free']
  timeline=['channelIndex\\tstage\\trouteRevision\\toriginSample\\tglobalStart\\tglobalEnd\\tfileSampleStart\\tsampleCount\\tsequence\\tcapturedAtNs']
  for channel in channels:
   folder='' if channel<0 else 'channel-'+str(channel)+'/'
   channel_candidate=candidate and not (mode=='partial-channel-gap' and channel==2)
   for stage in ['truth','asr']:
    src=f('files/guardian-diagnostics/'+prefix+'-'+stage+'.wav')
    data=src.read_bytes()[44:]
    # Every physical channel has distinguishable binary audio.
    data=bytes((x+channel+1)%256 for x in data)
    for sub,payload in [('',data)]+([('segment-1/',data[:32000])] if channel_candidate else []):
     dst=f(root+folder+sub+prefix+'-'+stage+'.wav');dst.parent.mkdir(parents=True,exist_ok=True)
     with wave.open(str(dst),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(payload)
     samples=struct.unpack('<'+'h'*(len(payload)//2),payload)
     dst.with_suffix('.f32').write_bytes(struct.pack('<'+'f'*len(samples),*(x/32768 for x in samples)))
     f(root+folder+sub+prefix+'-index.tsv').write_text('stage\\tsequence\\tcapturedAtNs\\tfileSampleStart\\tsampleCount\\n'+stage+'\\t1\\t20000\\t0\\t'+str(len(samples))+'\\n')
    if channel==1:
     # Late origin and a genuine global gap remain in the shared capture clock.
     for rev,start,file_start in [(1,16000,0),(2,48000,16000)]:
      global_start=0 if mode=='timeline-shift' and rev==1 else start
      timeline.append(f'{channel}\\t{stage}\\t{rev}\\t{start}\\t{global_start}\\t{global_start+16000}\\t{file_start}\\t16000\\t1\\t20000')
    else:timeline.append(f'{channel}\\t{stage}\\t1\\t0\\t0\\t32000\\t0\\t32000\\t1\\t20000')
   if not channel_candidate and candidate:emit('CONTINUOUS_STUDY_CHANNEL_WINDOW_SKIPPED',segment=1,channelIndex=channel,reason='CHANNEL_COVERAGE_GAP')
   if channel_candidate:
    lane='WIENER_DD_ADAPTIVE_MONO' if channel<0 else 'WIENER_DD_ADAPTIVE_CHANNEL_'+str(channel)
    emit('CONTINUOUS_STUDY_WINDOW',segment=1,channelIndex=channel,samples=16000,startSample=16000,endSample=32000,primaryAccepted=True,channelOriginalAccepted=channel!=1,originalAccepted=channel!=1,enhancedAccepted=True,rawRejectedFilteredAccepted=channel==1 and not bypass,calibrationReady=not bypass,calibrationValid=not bypass,startupBypass=bypass,calibrationReason='CONTAMINATED' if bypass else 'READY',role='SHADOW_NO_ALERT')
    if not bypass:
     emit('CONTINUOUS_STUDY_ASR_OFFER',segment=1,channelIndex=channel,lane=lane,accepted=True)
     if mode!='lane-mismatch' or channel!=1:
      emit('STUDY_QWEN_RESULT',segment=1,lane=lane,eligible=True,success=True,text='channel '+str(channel),role='SHADOW_NO_ALERT')
  if mode=='lane-mismatch':emit('STUDY_QWEN_RESULT',segment=1,lane='WIENER_DD_ADAPTIVE_CHANNEL_9',eligible=True,success=True,text='wrong channel',role='SHADOW_NO_ALERT')
  emit('STUDY_QWEN_RESULT',segment=9,lane='GTCRN_CONTINUOUS',eligible=True,success=True,text='unrelated',role='SHADOW_NO_ALERT')
  f(root+'channel-timeline.tsv').write_text('\\n'.join(timeline)+'\\n')
  if mode=='missing-study-float':f(root+'channel-2/'+prefix+'-asr.f32').unlink()
  if mode=='study-window-skipped':emit('CONTINUOUS_STUDY_WINDOW_SKIPPED',segment=2,reason='CHANNEL_COVERAGE_GAP')
  if mode=='missing-channel-payload':emit('CONTINUOUS_STUDY_CHANNEL_PAYLOAD_MISSING',sequence=2,startSample=32000,samples=800,reason='MULTICHANNEL_PAYLOAD_REQUIRED')
  emit('CONTINUOUS_STUDY_END',missingChannelPayloads=1 if mode=='missing-channel-payload' else 0,valid=True,inputSamples=64000,outputSamples=32000*len(channels),outputSamplesScope='SUM_OF_CHANNEL_OUTPUTS',pairedWindows=1 if candidate else 0,channelPairs=(len(channels)-(1 if mode=='partial-channel-gap' else 0)) if candidate else 0,channelCount=len(channels),incompleteWindows=0,processingScope='PER_CHANNEL_COHERENT')
  for channel in range(3):
   if channel==2 and mode=='adaptive-missing-channel':continue
   for suffix in ['raw.wav','raw.f32','index.tsv']:
    src=f('files/guardian-diagnostics/'+prefix+'-'+suffix)
    dst=f('files/guardian-diagnostics/adaptive-channels/abc12345/channel-'+str(channel)+'/'+prefix+'-'+suffix)
    dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
  f('files/guardian-diagnostics/adaptive-channels/abc12345/selection.tsv').write_text('sequence\\tsamples\\tselected\\n1\\t800\\t0\\n')
  with f('events').open('a') as h:h.write('10-27 18:00:00.000 123 123 I GuardianAudioEngine: ADAPTIVE_CHANNELS_WAV_SAVED path=fixture frames=40 dropped=0 limited=false\\n')
""")
replace("   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')", "   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')\n   with f('events').open('a') as h:h.write('10-27 18:00:00.000 123 123 I GuardianAudioEngine: ADAPTIVE_CHANNELS_READY channels=3 enabled=true indexMask=7\\n')")
replace(" if c[0]=='id':", " if c[0]=='find':print('\\n'.join(str(p.relative_to(r)) for p in f(c[1]).rglob('*') if p.is_file()))\n elif c[0]=='id':")
replace(' sys.stdout.buffer.write(b)', " if mode=='study-corrupt' and '/continuous-study/' in a[-1] and a[-1].endswith('asr.f32'):b=b[:-1]+bytes([b[-1]^255])\n sys.stdout.buffer.write(b)")

collector = (repo / 'enregistrer-guardian-canaux-coherents.ps1').read_text(encoding='utf-8-sig')
minimum_guard = '$TestCase -ne "FREE" -and $DurationSeconds -lt 75'
assert minimum_guard in collector
with tempfile.TemporaryDirectory(prefix='guardian-channel-coherence-collector-') as tmp:
    accelerated = Path(tmp) / 'annotated-fixture.ps1'
    accelerated.write_text(collector.replace(minimum_guard, '$TestCase -ne "FREE" -and $DurationSeconds -lt 1'), encoding='utf-8-sig')
    modes = ['ok', 'contaminated-free', 'phrases-no-calibration', 'words-no-candidate',
             'silence-no-candidate', 'wrong-study-model', 'wrong-processing-scope',
             'study-window-skipped', 'missing-study-float', 'timeline-shift',
             'study-corrupt', 'adaptive-missing-channel', 'lane-mismatch', 'mono',
             'partial-channel-gap', 'missing-channel-payload']
    selected = os.environ.get('COHERENCE_COLLECTOR_MODES')
    if selected:
        modes = selected.split(',')
    for mode in modes:
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
        test_case = 'PHRASES' if mode == 'phrases-no-calibration' else 'WORDS' if mode == 'words-no-candidate' else 'SILENCE' if mode == 'silence-no-candidate' else 'FREE'
        script = accelerated if test_case != 'FREE' else repo / 'enregistrer-guardian-canaux-coherents.ps1'
        cmd = [os.environ.get('PWSH', 'pwsh'), '-NoProfile', '-File', str(script),
               '-ProjectDirectory', str(project), '-PackageName', 'com.example.test',
               '-DurationSeconds', '1', '-TestCase', test_case]
        run = subprocess.run(cmd, env=env, input='fixture\n\n\nconforme\n', text=True,
                             capture_output=True, timeout=60)
        expected_ok = mode in ['ok', 'contaminated-free', 'silence-no-candidate', 'lane-mismatch', 'mono', 'partial-channel-gap']
        assert (run.returncode == 0) == expected_ok, mode + '\n' + run.stdout + run.stderr
        archives = list((project / 'diagnostics-voix').glob('*.zip'))
        assert len(archives) == 1
        with zipfile.ZipFile(archives[0]) as z:
            m = json.loads(z.read('session.json').decode('utf-8-sig'))
            assert m['completed'] == expected_ok and m['controlsRestored']
            assert m['collectorVersion'] == 'channel-coherence-3.4.7'
            assert m['captureRouteRequested'] == 'AUTO' and m['profile'] == 'DISTANT'
            assert m['processingModeRequested'] == 'WIENER_STUDY'
            if mode in ['wrong-study-model', 'wrong-processing-scope']:
                assert 'PER_CHANNEL_COHERENT' in m['error']
            elif mode == 'study-corrupt':
                assert 'Copie incorrecte' in m['error']
            else:
                assert m['continuousStudyReady']['processingScope'] == 'PER_CHANNEL_COHERENT'
                assert m['continuousStudy']['outputSamplesScope'] == 'SUM_OF_CHANNEL_OUTPUTS'
                assert z.read('continuous-study/channel-timeline.tsv')
                # Study validation failures still retrieve every available raw channel.
                expected_raw = 2 if mode == 'adaptive-missing-channel' else 3
                assert len([n for n in z.namelist() if n.startswith('adaptive-channels/') and n.endswith('.f32')]) == expected_raw
                assert len([n for n in z.namelist() if n.startswith('wav/') and n.endswith('.wav')]) == 5
                if mode in ['phrases-no-calibration', 'words-no-candidate']:
                    assert 'non comparative' in m['error']
                    assert m['filteredComparisonWindows'] == 0
                    assert not m['comparativeStudy']
                    assert z.read('meminfo-after.txt')
                if mode == 'missing-study-float':
                    assert 'Charge utile' in m['error']
                if mode == 'missing-channel-payload':
                    assert 'Charge utile multicanal perdue' in m['error']
                    assert len(m['continuousStudyMissingChannelPayloads']) == 1
                if mode == 'timeline-shift':
                    assert 'Chronologie globale incoherente' in m['error']
                if mode == 'study-window-skipped':
                    assert not m['continuousStudyWindowsComplete']
                if expected_ok:
                    assert m['studyValidationErrors'] == []
                    assert m['wienerStudy']['mainQwenInput'] == 'ORIGINAL'
                    assert not m['wienerStudy']['gtcrnEnabled']
                    by_channel = {x['channelIndex']: x for x in m['perChannelComparisons']}
                    if mode == 'mono':
                        assert set(by_channel) == {-1}
                        assert m['coherentGateResults'][0]['results'][0]['lane'] == 'WIENER_DD_ADAPTIVE_MONO'
                    else:
                        assert set(by_channel) == {0, 1, 2}
                    if mode == 'silence-no-candidate':
                        assert m['filteredComparisonWindows'] == 0
                        assert m['continuousStudyWindows'] == []
                        assert m['diagnosticAsrComplete']
                        assert all(x['windows'] == 0 for x in by_channel.values())
                    elif mode == 'contaminated-free':
                        assert m['wienerStudy']['startupBypassWindows'] == 3
                        assert m['diagnosticAsrOffered'] == 0
                    else:
                        assert m['filteredComparisonWindows'] == (1 if mode == 'mono' else 2 if mode == 'partial-channel-gap' else 3)
                        assert m['diagnosticAsrComplete'] == (mode != 'lane-mismatch')
                        for a in m['coherentGateResults']:
                            if mode == 'lane-mismatch' and a['channelIndex'] == 1:
                                assert a['resultCount'] == 0
                            else:
                                assert a['resultCount'] == 1
                                expected_lane = 'WIENER_DD_ADAPTIVE_MONO' if a['channelIndex'] == -1 else 'WIENER_DD_ADAPTIVE_CHANNEL_' + str(a['channelIndex'])
                                assert a['results'][0]['lane'] == expected_lane
                        if mode == 'partial-channel-gap':
                            assert by_channel[2]['windows'] == 0 and by_channel[2]['skippedWindows'] == 1
                            assert len(m['continuousStudyChannelWindowsSkipped']) == 1
                        if mode != 'mono':
                            assert by_channel[1]['originalAcceptedWindows'] == 0
                            assert by_channel[1]['rawRejectedFilteredAcceptedWindows'] == 1
                            timeline = z.read('continuous-study/channel-timeline.tsv').decode()
                            assert '1\ttruth\t1\t16000\t16000\t32000\t0\t16000' in timeline
                            assert '1\ttruth\t2\t48000\t48000\t64000\t16000\t16000' in timeline
                            full = [n for n in z.namelist() if n.startswith('continuous-study/channel-') and '/segment-' not in n and n.endswith('truth.f32')]
                            assert len({hashlib.sha256(z.read(n)).hexdigest() for n in full}) == 3
                    manifest = json.loads(z.read('wav-manifest.json').decode('utf-8-sig'))
                    for record in manifest:
                        name = record['file'] if '/' in record['file'] else 'wav/' + record['file']
                        assert hashlib.sha256(z.read(name)).hexdigest() == record['sha256']
        assert not list((d / 'remote/files').glob('guardian-*.txt'))
        print('PASS channel coherence collector', mode, flush=True)
