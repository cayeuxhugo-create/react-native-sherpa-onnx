"""Exercise the v3.4.8 FSMN collector with synthetic ADB, never a real phone.

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


replace('guardian-voice-v3.4.3-speech-gain-study', 'guardian-voice-v3.4.8-fsmn-confirmation')
replace("elif a[:2]==['shell','getprop']", "elif a[:2]==['shell','dumpsys']:print('Synthetic memory report')\nelif a[:2]==['shell','getprop']")
replace("if event=='WAV_READY':emit(", "if event=='WAV_READY':emit('CONTINUOUS_STUDY_READY',model='GTCRN' if mode=='wrong-study-model' else 'WIENER_DD_ADAPTIVE',role='SHADOW_NO_ALERT',processingScope='MIXED_MONO' if mode=='wrong-processing-scope' else 'PER_CHANNEL_COHERENT',alignment='WIENER_PER_CHANNEL_FINALIZED_SAMPLE_CLOCK',maxChannels=30)\n  if event=='WAV_READY':emit(")
replace("if os.environ.get('FAKE_FIRERED') in ['3','4','5']:", "if os.environ.get('FAKE_FIRERED') in ['3','4','5'] and f('files/guardian-voice-processing.txt').read_text().strip()!='WIENER_STUDY':")
replace('gtcrnEnabled=True', 'gtcrnEnabled=False')
replace(" if a[-1]=='SIMPLE_RECORD_END':", """ if a[-1]=='SIMPLE_RECORD_END':
  profile=f('files/guardian-voice-profile.txt').read_text().strip()
  prefix='guardian-voice-1792424800000-'+profile+'-abc12345'
  root='files/guardian-diagnostics/continuous-study/abc12345/'
  channels=[0,1]
  candidate=mode!='fsmn-no-candidate'
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
  for channel in range(2):
   if channel==2 and mode=='adaptive-missing-channel':continue
   for suffix in ['raw.wav','raw.f32','index.tsv']:
    src=f('files/guardian-diagnostics/'+prefix+'-'+suffix)
    dst=f('files/guardian-diagnostics/adaptive-channels/abc12345/channel-'+str(channel)+'/'+prefix+'-'+suffix)
    dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
  f('files/guardian-diagnostics/adaptive-channels/abc12345/selection.tsv').write_text('sequence\\tsamples\\tselected\\n1\\t800\\t0\\n')
  with f('events').open('a') as h:h.write('10-27 18:00:00.000 123 123 I GuardianAudioEngine: ADAPTIVE_CHANNELS_WAV_SAVED path=fixture frames=40 dropped=0 limited=false\\n')
""")
replace("   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')", "   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')\n   with f('events').open('a') as h:h.write('10-27 18:00:00.000 123 123 I GuardianAudioEngine: ADAPTIVE_CHANNELS_READY channels=2 enabled=true indexMask=7\\n')")
replace(" if c[0]=='id':", " if c[0]=='find':print('\\n'.join(str(p.relative_to(r)) for p in f(c[1]).rglob('*') if p.is_file()))\n elif c[0]=='id':")
replace(' sys.stdout.buffer.write(b)', " if mode=='study-corrupt' and '/continuous-study/' in a[-1] and a[-1].endswith('asr.f32'):b=b[:-1]+bytes([b[-1]^255])\n sys.stdout.buffer.write(b)")

replace(" if os.environ.get('FAKE_FIRERED') in ['1','2','3','4','5']:", " if event=='SESSION_START':kw['fsmnConfirmationRequired']=mode!='fsmn-not-required';kw['fsmnModel']='FSMN_OFFLINE_NCNN_FP32';kw['fsmnTargetPeak']=.4;kw['fsmnMaxInputGain']=64\n if os.environ.get('FAKE_FIRERED') in ['1','2','3','4','5']:")
replace("  if event=='WAV_READY':emit('CONTINUOUS_STUDY_READY'", "  if event=='WAV_READY' and mode in ['fsmn-start-failed','voice-gate-start-failed','old-firered-start-failed']:emit('VOICE_GATE_START_FAILED' if mode=='voice-gate-start-failed' else 'FIRERED_START_FAILED' if mode=='old-firered-start-failed' else 'FSMN_START_FAILED',model='FSMN_OFFLINE_NCNN_FP32',reason='ASSET_MISSING');return\n  if event=='WAV_READY' and mode!='fsmn-missing-ready':emit('FSMN_READY',model='OTHER_MODEL' if mode=='fsmn-wrong-model' else 'FSMN_OFFLINE_NCNN_FP32',required=True,source='PRIMARY_ORIGINAL_PCM',targetPeak=.4,maxInputGain=64,minInputGain=1,frameLength=400,frameShift=160)\n  if event=='WAV_READY':emit('CONTINUOUS_STUDY_READY'")
replace(" if a[-1]=='SIMPLE_RECORD_END':", """ if a[-1]=='SIMPLE_RECORD_END':
  if mode=='fsmn-error-annotated':emit('FSMN_ERROR',model='FSMN_OFFLINE_NCNN_FP32',segment=1,reason='INFERENCE_ERROR')
  if mode=='fsmn-unavailable-annotated':emit('FSMN_UNAVAILABLE',model='FSMN_OFFLINE_NCNN_FP32',segment=1,reason='ASSET_UNAVAILABLE')
  if mode!='fsmn-no-candidate':
   skipped=mode=='fsmn-skip-original'
   failure=mode=='fsmn-result-failure'
   rejected=mode=='fsmn-reject'
   if mode!='fsmn-missing-result':emit('FSMN_RESULT',segment=1,model='FSMN_OFFLINE_NCNN_FP32',source='PRIMARY_ORIGINAL_PCM',evaluated=not skipped,success=not skipped and not failure,accepted=not skipped and not failure and not rejected,reason='SKIPPED_ORIGINAL_REJECT' if skipped else 'INFERENCE_ERROR' if failure else 'REJECTED' if rejected else 'CONFIRMED',inputPeak=.02,inputGain=20.,conditionedPeak=.4,posteriorFrames=198,speechFrames=0 if rejected else 80,detectedSegments=0 if rejected else 1,inferenceMs=4,confirmationMs=5,confirmationCpuMs=4)
   emit('FIRERED_RESULT',segment=1,accepted=not skipped and not failure and not rejected,admissionReason='FIRERED_REJECT' if skipped else 'FSMN_ERROR' if failure else 'FSMN_REJECT' if rejected else 'NEURAL_CONFIRMED',fsmnConfirmationRequired=True)
""")
replace("     emit('CONTINUOUS_STUDY_ASR_OFFER',segment=1,channelIndex=channel,lane=lane,accepted=True)", "     emit('CONTINUOUS_STUDY_ASR_OFFER',segment=1,channelIndex=channel,lane=lane,accepted=not (mode=='diagnostic-offer-declined' and channel==1),reason='QUEUE_FULL' if mode=='diagnostic-offer-declined' and channel==1 else 'QUEUED',pendingDiagnosticSlots=2)")
replace("     if mode!='lane-mismatch' or channel!=1:", "     if channel!=1 or mode not in ['partial-diagnostic','diagnostic-offer-declined']:")

replace("     emit('CONTINUOUS_STUDY_ASR_OFFER',", "     emit('STUDY_QWEN_OFFER',segment=1,lane=lane,accepted=not (mode=='diagnostic-offer-declined' and channel==1),status='QUEUE_FULL' if mode=='diagnostic-offer-declined' and channel==1 else 'QUEUED',pendingDiagnostics=channel+1,maxPendingDiagnostics=2,role='SHADOW_NO_ALERT')\n     emit('CONTINUOUS_STUDY_ASR_OFFER',")
replace("      emit('STUDY_QWEN_RESULT',segment=1,lane=lane,eligible=True,success=True,text='channel '+str(channel),role='SHADOW_NO_ALERT')", "      emit('STUDY_QWEN_RESULT',segment=1,lane=lane,eligible=not (mode=='diagnostic-dropped' and channel==1),success=not (mode in ['diagnostic-dropped','diagnostic-failed'] and channel==1),status='DROPPED' if mode=='diagnostic-dropped' and channel==1 else 'FAILED' if mode=='diagnostic-failed' and channel==1 else 'EMPTY' if mode=='diagnostic-empty' and channel==1 else 'DECODED',reason='DECODER_CLOSED' if mode=='diagnostic-dropped' and channel==1 else 'DECODE_FAILED' if mode=='diagnostic-failed' and channel==1 else None,text='' if mode=='diagnostic-empty' and channel==1 else 'channel '+str(channel),role='SHADOW_NO_ALERT')")

collector = (repo / 'enregistrer-guardian-confirmation.ps1').read_text(encoding='utf-8-sig')
minimum_guard = '$TestCase -ne "FREE" -and $DurationSeconds -lt 75'
assert minimum_guard in collector
with tempfile.TemporaryDirectory(prefix='guardian-fsmn-collector-') as tmp:
    accelerated = Path(tmp) / 'annotated-fixture.ps1'
    accelerated.write_text(collector.replace(minimum_guard, '$TestCase -ne "FREE" -and $DurationSeconds -lt 1'), encoding='utf-8-sig')
    modes = ['ok', 'fsmn-missing-ready', 'fsmn-wrong-model', 'fsmn-not-required',
             'fsmn-start-failed', 'voice-gate-start-failed', 'old-firered-start-failed', 'fsmn-error-annotated', 'fsmn-unavailable-annotated',
             'fsmn-reject', 'fsmn-skip-original', 'fsmn-result-failure',
             'fsmn-no-candidate', 'partial-diagnostic', 'diagnostic-offer-declined',
             'study-corrupt', 'timeline-shift', 'diagnostic-dropped', 'diagnostic-failed', 'diagnostic-empty', 'fsmn-missing-result']
    selected = os.environ.get('FSMN_COLLECTOR_MODES')
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
        test_case = 'PHRASES' if mode in ['fsmn-error-annotated','fsmn-unavailable-annotated'] else 'SILENCE' if mode == 'fsmn-no-candidate' else 'FREE'
        script = accelerated if test_case != 'FREE' else repo / 'enregistrer-guardian-confirmation.ps1'
        cmd = [os.environ.get('PWSH', 'pwsh'), '-NoProfile', '-File', str(script),
               '-ProjectDirectory', str(project), '-PackageName', 'com.example.test',
               '-DurationSeconds', '1', '-TestCase', test_case]
        run = subprocess.run(cmd, env=env, input='fixture\n\n\nconforme\n', text=True,
                             capture_output=True, timeout=60)
        expected_ok = mode in ['ok', 'fsmn-reject', 'fsmn-skip-original',
                               'fsmn-no-candidate', 'partial-diagnostic', 'diagnostic-offer-declined',
                               'diagnostic-dropped', 'diagnostic-failed', 'diagnostic-empty']
        assert (run.returncode == 0) == expected_ok, mode + '\n' + run.stdout + run.stderr
        archives = list((project / 'diagnostics-voix').glob('*.zip'))
        assert len(archives) == 1
        with zipfile.ZipFile(archives[0]) as z:
            m = json.loads(z.read('session.json').decode('utf-8-sig'))
            assert m['completed'] == expected_ok and m['controlsRestored']
            assert m['collectorVersion'] == 'fsmn-confirmation-3.4.8'
            assert m['captureRouteRequested'] == 'AUTO' and m['profile'] == 'DISTANT'
            assert m['processingModeRequested'] == 'WIENER_STUDY'
            fsmn = m['fsmnConfirmation']
            assert fsmn['expectedModel'] == 'FSMN_OFFLINE_NCNN_FP32'
            assert fsmn['targetPeak'] == .4 and fsmn['maxInputGain'] == 64
            if mode in ['fsmn-start-failed','voice-gate-start-failed','old-firered-start-failed']:
                assert 'FSMN' in m['error'] and not fsmn['startupConfirmed']
                assert any(e['event'] in ['FSMN_START_FAILED','VOICE_GATE_START_FAILED','FIRERED_START_FAILED'] for e in fsmn['errors'])
            elif mode == 'study-corrupt':
                assert 'Copie incorrecte' in m['error']
            else:
                # Failed FSMN blocks still retrieve every finalized main/study/raw file.
                assert len([n for n in z.namelist() if n.startswith('adaptive-channels/') and n.endswith('.f32')]) == 2
                assert len([n for n in z.namelist() if n.startswith('wav/') and n.endswith('.wav')]) == 5
                assert z.read('continuous-study/channel-timeline.tsv') and z.read('meminfo-after.txt')
                assert m['continuousStudyReady']['processingScope'] == 'PER_CHANNEL_COHERENT'
                if mode in ['fsmn-missing-ready','fsmn-wrong-model','fsmn-not-required']:
                    assert not fsmn['startupConfirmed'] and not m['fsmnStartupConfirmed']
                    assert m['studyValidationErrors']
                else:
                    assert fsmn['startupConfirmed'] and fsmn['requiredBySession']
                if mode in ['fsmn-error-annotated','fsmn-unavailable-annotated','fsmn-result-failure']:
                    assert 'Confirmation FSMN' in m['error']
                    if mode == 'fsmn-result-failure':
                        assert fsmn['failed'] == 1
                    else:
                        assert len(fsmn['errors']) == 1
                if mode == 'fsmn-missing-result':
                    assert 'Preuve FSMN absente' in m['error']
                    assert not m['fsmnAdmissionChecks'][0]['consistent']
                if mode == 'fsmn-reject':
                    assert fsmn['evaluated'] == fsmn['rejected'] == 1
                    assert fsmn['admitted'] == fsmn['failed'] == fsmn['skippedEarlierGate'] == 0
                    assert m['primaryAdmissionEvents'][0]['admissionReason'] == 'FSMN_REJECT'
                if mode == 'fsmn-skip-original':
                    assert fsmn['skippedEarlierGate'] == fsmn['skippedOriginalReject'] == 1
                    assert fsmn['evaluated'] == fsmn['rejected'] == fsmn['failed'] == 0
                if mode == 'fsmn-no-candidate':
                    assert fsmn['results'] == [] and fsmn['evaluated'] == 0
                    assert m['continuousStudyWindows'] == [] and m['diagnosticAsrCoverageComplete']
                if mode == 'timeline-shift':
                    assert 'Chronologie globale incoherente' in m['error']
                if expected_ok:
                    assert m['studyValidationErrors'] == []
                    assert all(c['consistent'] for c in m['fsmnAdmissionChecks'])
                    assert m['wienerStudy']['mainQwenInput'] == 'ORIGINAL'
                    assert not m['wienerStudy']['gtcrnEnabled']
                    by_channel = {x['channelIndex']: x for x in m['perChannelComparisons']}
                    assert set(by_channel) == {0,1}
                    if mode not in ['fsmn-no-candidate']:
                        assert m['diagnosticAsrRequested'] == 2
                        assert m['diagnosticAsrPartial'] == (mode in ['partial-diagnostic','diagnostic-offer-declined','diagnostic-dropped','diagnostic-failed'])
                        assert m['diagnosticAsrCoverageComplete'] == (mode not in ['partial-diagnostic','diagnostic-offer-declined','diagnostic-dropped','diagnostic-failed'])
                        assert m['diagnosticAsrQueueLimits'] == [2]
                        if mode in ['diagnostic-dropped','diagnostic-failed']:
                            field='diagnosticAsrDropped' if mode=='diagnostic-dropped' else 'diagnosticAsrFailed'
                            assert m[field] == 1 and by_channel[1][field] == 1
                            assert m['diagnosticAsrResults'] == 1 and not m['diagnosticAsrComplete']
                            channel1=next(a for a in m['coherentGateResults'] if a['channelIndex']==1)
                            assert channel1['resultCount'] == 0 and channel1['terminalCount'] == 1
                        elif mode == 'diagnostic-offer-declined':
                            assert m['diagnosticAsrDeclined'] == 1 and m['diagnosticAsrComplete']
                            assert by_channel[1]['diagnosticAsrDeclined'] == 1
                        elif mode == 'partial-diagnostic':
                            assert m['diagnosticAsrDeclined'] == 0 and not m['diagnosticAsrComplete']
                            assert by_channel[1]['diagnosticAsrOffered'] == 1 and by_channel[1]['diagnosticAsrResults'] == 0
                        else:
                            assert m['diagnosticAsrOffered'] == m['diagnosticAsrResults'] == 2
                            assert m['diagnosticAsrComplete']
                            for a in m['coherentGateResults']:
                                assert a['resultCount'] == 1
                                assert a['results'][0]['lane'] == 'WIENER_DD_ADAPTIVE_CHANNEL_' + str(a['channelIndex'])
                            assert by_channel[0]['diagnosticAsrResults'] == by_channel[1]['diagnosticAsrResults'] == 1
                            assert m['diagnosticAsrEmpty'] == (1 if mode=='diagnostic-empty' else 0)
                        if fsmn['evaluated']:
                            r=fsmn['results'][0]
                            assert r['inputGain'] == 20. and r['inputPeak'] == .02 and r['conditionedPeak'] == .4
                    manifest = json.loads(z.read('wav-manifest.json').decode('utf-8-sig'))
                    for record in manifest:
                        name = record['file'] if '/' in record['file'] else 'wav/' + record['file']
                        assert hashlib.sha256(z.read(name)).hexdigest() == record['sha256']
        assert not list((d / 'remote/files').glob('guardian-*.txt'))
        print('PASS FSMN collector', mode, flush=True)
