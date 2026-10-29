"""Run the reference/noise PowerShell collector end to end against synthetic ADB.

PWSH may point at a local PowerShell installation. Only a temporary collector copy
shortens startup/finalization deadlines; production guards remain unchanged.
No device, network, private audio, or recognition model is used.
"""
from pathlib import Path
import csv
import hashlib
import io
import json
import os
import subprocess
import tempfile
import zipfile


REPO = Path(__file__).resolve().parents[3]
# Both releases use the Windows PowerShell 5.1-compatible validator already
# exercised with the targeted dictionary-property regression test.
def validator(name):
    text = (REPO / name).read_text(encoding='utf-8-sig')
    return text.split('# BEGIN REFERENCE EXPORT VALIDATION', 1)[1].split('# END REFERENCE EXPORT VALIDATION', 1)[0]
assert validator('enregistrer-guardian-micros.ps1') == validator('enregistrer-guardian-reference.ps1')
source = (REPO / 'etude-silence/fake_adb.py').read_text()


def replace(old, new):
    global source
    assert old in source, f'Fixture changed: {old!r}'
    source = source.replace(old, new)


replace('import sys,os,pathlib,json,time,hashlib,shutil,wave,struct',
        'import sys,os,pathlib,json,time,hashlib,shutil,wave,struct,csv')
replace('guardian-voice-v3.4.3-speech-gain-study',
        'guardian-voice-v3.5.2-microphone-selection')
replace("elif a[:2]==['shell','getprop']",
        "elif a[:2]==['shell','dumpsys']:print('Synthetic memory report')\n"
        "elif a[:2]==['shell','getprop']")
replace(" if c[0]=='id':",
        " if c[0]=='find':print('\\n'.join(str(p.relative_to(r)) for p in sorted(f(c[1]).rglob('*')) if p.is_file()))\n"
        " elif c[0]=='id':")
replace(" if os.environ.get('FAKE_FIRERED') in ['1','2','3','4','5']:", """ if event=='SESSION_START':
  kw.update(fsmnConfirmationRequired=True,fsmnModel='FSMN_OFFLINE_NCNN_FP32',fsmnTargetPeak=.4,fsmnMaxInputGain=64,fsmnExecutionBackend='NCNN_SERIAL_CPU',referenceNoiseStudy=True,studyModel='WIENER_CROSS_CHANNEL_REFERENCE',referenceNoiseRole='PRODUCT_ALERT' if mode=='wrong-session-role' else 'SHADOW_NO_ALERT',referenceNoiseProfiles='SHORT_0P5_3_AND_LONG_2_15',referenceNoiseContextSamples=32000,referenceNoiseMaxAsrSamples=128000,referenceNoiseCalibrationAssumption='PROTOCOL_NO_SPEECH_PREFIX_UNVERIFIED',retainedMonoWienerModel='WIENER_DD_ADAPTIVE',referenceNoiseWindowSources='PRIMARY_ORIGINAL_AND_FILTERED_STREAM_LONG',referenceNoiseFilteredVadBlockSamples=800,referenceNoiseFilteredOfferPolicy='FIRERED_CONTEXT_PREFERRED_FSMN_LOGGED_ONLY',referenceNoiseMaxFilteredOffers=16)
 if event=='WAV_READY':
  emit('FSMN_READY',model='FSMN_OFFLINE_NCNN_FP32',required=True,source='PRIMARY_ORIGINAL_PCM',targetPeak=.4,maxInputGain=64,minInputGain=1,executionBackend='NCNN_SERIAL_CPU')
  emit('REFERENCE_STUDY_READY',model='WIENER_CROSS_CHANNEL_REFERENCE',role='PRODUCT_ALERT' if mode=='wrong-ready-role' else 'SHADOW_NO_ALERT',executionBackend='NCNN_SERIAL_CPU',profiles='SHORT_0P5_3_AND_LONG_2_15',contextSamples=32000,maxAsrSamples=128000,calibrationAssumption='PROTOCOL_NO_SPEECH_PREFIX_UNVERIFIED',diagnosticOfferPolicy='LONG_EXACT_AND_LONG_CONTEXT',maxPendingDiagnostics=2,windowSources='PRIMARY_ORIGINAL_AND_FILTERED_STREAM_LONG',fixedVadBlockSamples=800,filteredOfferPolicy='FIRERED_CONTEXT_PREFERRED_FSMN_LOGGED_ONLY',maxFilteredDiagnosticOffers=16)
 if os.environ.get('FAKE_FIRERED') in ['1','2','3','4','5']:
""")
replace("if os.environ.get('FAKE_FIRERED') in ['3','4','5']:",
        "if os.environ.get('FAKE_FIRERED') in ['3','4','5'] and f('files/guardian-voice-processing.txt').read_text().strip()!='REFERENCE_NOISE_STUDY':")
replace('gtcrnEnabled=True', 'gtcrnEnabled=False')
replace("channels=2 if multi=='VOICE_FOCUS' else 1",
        "channels=1 if mode=='mono' else 2")
replace("   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')", """   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')
   with f('events').open('a') as h:h.write('10-27 18:00:00.000 123 123 I GuardianAudioEngine: ADAPTIVE_CHANNELS_READY channels='+str(channels)+' enabled='+('false' if mode=='mono' else 'true')+' indexMask=3\\n')
""")
replace(' sys.stdout.buffer.write(b)', """ if mode=='corrupt-copy' and '/reference-study/' in a[-1] and '/EXACT/' in a[-1] and a[-1].endswith('asr.f32'):b=b[:-1]+bytes([b[-1]^255])
 sys.stdout.buffer.write(b)""")
replace(" if a[-1]=='SIMPLE_RECORD_END':", """ if a[-1]=='SIMPLE_RECORD_END':
  profile=f('files/guardian-voice-profile.txt').read_text().strip()
  prefix='guardian-voice-1792424800000-'+profile+'-abc12345'
  root=f('files/guardian-diagnostics/reference-study/abc12345')
  root.mkdir(parents=True,exist_ok=True)
  rows=[];timeline=[];origin=32000;full_samples=320000
  def write_audio(relative,data,kind,lane,start,samples,variant='FULL'):
   base=root/relative;base.parent.mkdir(parents=True,exist_ok=True)
   base.with_suffix('.f32').write_bytes(data)
   values=struct.unpack('<'+'f'*samples,data)
   with wave.open(str(base.with_suffix('.wav')),'wb') as w:
    w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000)
    w.writeframes(struct.pack('<'+'h'*samples,*(round(value*32768) for value in values)))
   for extension in ['wav','f32']:
    rows.append(dict(path=relative+'.'+extension,kind=kind,epoch=1,segment=0 if kind=='full' else 1,variant=variant,lane=lane,globalStart=start,samples=samples))
  if mode!='mono':
   for i,lane in enumerate(['primary','reference','short','long']):
    stage=['truth','raw','pregain','asr'][i]
    data=struct.pack('<'+'f'*full_samples,*((float(n%127+i*7)/1024) for n in range(full_samples)))
    write_audio('epoch-1/'+prefix+'-'+stage,data,'full',lane,origin,full_samples)
    for offset in [0,160000]:
     timeline.append(dict(epoch=1,lane=lane,routeRevision=2,originSample=origin,globalStart=origin+offset,globalEnd=origin+offset+160000,fileSampleStart=offset,sampleCount=160000,sequence=offset//800+41,capturedAtNs=1000000+offset*62500))
    for variant,samples in [('EXACT',8000),('END_PLUS_2S',40000)]:
     offset=256000
     write_audio('epoch-1/segment-1/'+variant+'/'+prefix+'-'+stage,data[offset*4:(offset+samples)*4],'pair',lane,origin+offset,samples,variant)
   for folder,segment,variant in [('epoch-1',0,'FULL'),('epoch-1/segment-1/EXACT',1,'EXACT'),('epoch-1/segment-1/END_PLUS_2S',1,'END_PLUS_2S')]:
    name=folder+'/'+prefix+'-index.tsv'
    (root/name).write_text('stage\\tsequence\\tcapturedAtNs\\tfileSampleStart\\tsampleCount\\ntruth\\t41\\t1000000\\t0\\t800\\n')
    rows.append(dict(path=name,kind='index',epoch=1,segment=segment,variant=variant,lane='index',globalStart=origin,samples=0))
   for variant,samples in [('EXACT',8000),('END_PLUS_2S',40000)]:
    emit('REFERENCE_STUDY_WINDOW',segment=1,variant=variant,epoch=1,available=True,status='FILTERED',startSample=origin+256000,endSample=origin+256000+samples,samples=samples,primaryChannel=0,referenceChannel=1,primaryOriginalAccepted=False,shortReady=True,longReady=True,calibrationAssumption='PROTOCOL_NO_SPEECH_PREFIX_UNVERIFIED',filteredFireRedAccepted=False,filteredFsmnAccepted=True,filteredCombinedAccepted=False,diagnosticLane='REFERENCE_NOISE_LONG_'+variant,diagnosticOfferStatus='QUEUED',diagnosticForced=True,productionEligible=False,role='SHADOW_NO_ALERT')
    emit('STUDY_QWEN_RESULT',segment=1,lane='REFERENCE_NOISE_LONG_'+variant,eligible=True,success=True,status='DECODED',text='synthetic rejected candidate',role='SHADOW_NO_ALERT')
  else:
   emit('REFERENCE_STUDY_WINDOW',segment=1,variant='EXACT',available=False,status='PAIR_OR_ROUTE_BOUNDARY',diagnosticForced=True,productionEligible=False,role='SHADOW_NO_ALERT')
  for name,data,fields in [('reference-files.csv',rows,['path','kind','epoch','segment','variant','lane','globalStart','samples']),('reference-timeline.csv',timeline,['epoch','lane','routeRevision','originSample','globalStart','globalEnd','fileSampleStart','sampleCount','sequence','capturedAtNs'])]:
   with (root/name).open('w',newline='') as h:
    writer=csv.DictWriter(h,fieldnames=fields);writer.writeheader();writer.writerows(data)
  if mode=='missing-file':
   (root/('epoch-1/segment-1/END_PLUS_2S/'+prefix+'-asr.f32')).unlink()
  if mode=='pair-corrupt':
   target=root/('epoch-1/segment-1/EXACT/'+prefix+'-asr.f32')
   payload=bytearray(target.read_bytes());payload[0]^=1;target.write_bytes(payload)
  emit('REFERENCE_STUDY_END',valid=True,inputSamples=origin+full_samples,pairEverAvailable=mode!='mono',epochCount=0 if mode=='mono' else 1,exactPairs=0 if mode=='mono' else 1,contextPairs=0 if mode=='mono' else 1,skippedWindows=1 if mode=='mono' else 0,diagnosticOffers=0 if mode=='mono' else 2,processingWallMs=20,threadCpuMs=15,filesManifest='reference-files.csv',timeline='reference-timeline.csv',role='SHADOW_NO_ALERT')
  if mode!='mono':
   for channel in range(2):
    for suffix in ['raw.wav','raw.f32','index.tsv']:
     src=f('files/guardian-diagnostics/'+prefix+'-'+suffix)
     dst=f('files/guardian-diagnostics/adaptive-channels/abc12345/channel-'+str(channel)+'/'+prefix+'-'+suffix)
     dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
   f('files/guardian-diagnostics/adaptive-channels/abc12345/selection.tsv').write_text('sequence\\tsamples\\tselected\\n1\\t800\\t0\\n')
   with f('events').open('a') as h:h.write('10-27 18:00:00.000 123 123 I GuardianAudioEngine: ADAPTIVE_CHANNELS_WAV_SAVED path=fixture frames=40 dropped=0 limited=false\\n')
""")


def read_json(archive, name):
    return json.loads(archive.read(name).decode('utf-8-sig'))


def read_csv(archive, name):
    return list(csv.DictReader(io.StringIO(archive.read(name).decode('utf-8-sig'))))


def assert_reference_audio(archive):
    """Independently check recovered lanes, clock offsets, and both pair variants."""
    rows = read_csv(archive, 'reference-study/reference-files.csv')
    timeline = read_csv(archive, 'reference-study/reference-timeline.csv')
    floats = [row for row in rows if row['path'].endswith('.f32')]
    full = {row['lane']: row for row in floats if row['kind'] == 'full'}
    assert set(full) == {'primary', 'reference', 'short', 'long'}
    assert len({hashlib.sha256(archive.read('reference-study/' + row['path'])).digest()
                for row in full.values()}) == 4
    assert {row['variant'] for row in floats if row['kind'] == 'pair'} == {'EXACT', 'END_PLUS_2S'}
    assert len([row for row in floats if row['kind'] == 'pair']) == 8
    for lane, row in full.items():
        clocks = [clock for clock in timeline if clock['lane'] == lane]
        assert len(clocks) == 2
        offset = 0
        for clock in clocks:
            assert int(clock['fileSampleStart']) == offset
            assert int(clock['globalStart']) == int(row['globalStart']) + offset
            offset += int(clock['sampleCount'])
        assert offset == int(row['samples'])
    for row in floats:
        payload = archive.read('reference-study/' + row['path'])
        assert len(payload) == int(row['samples']) * 4
        if row['kind'] == 'pair':
            continuous = full[row['lane']]
            offset = (int(row['globalStart']) - int(continuous['globalStart'])) * 4
            data = archive.read('reference-study/' + continuous['path'])
            assert payload == data[offset:offset + len(payload)]
    for row in rows:
        assert archive.read('reference-study/' + row['path'])


def run_scenarios():
    collector = (REPO / 'enregistrer-guardian-micros.ps1').read_text(encoding='utf-8-sig')
    # A bad startup role must fail promptly in this fixture, without altering
    # production timeouts or bypassing any validation.
    assert '.AddSeconds(60)' in collector
    accelerated = collector.replace('.AddSeconds(60)', '.AddSeconds(2)')
    accelerated = accelerated.replace('.AddSeconds(15)', '.AddSeconds(2)')
    accelerated = accelerated.replace('.AddSeconds(6)', '.AddSeconds(2)')
    modes = ['ok', 'mono', 'corrupt-copy', 'pair-corrupt', 'missing-file',
             'wrong-ready-role', 'wrong-session-role']
    selected = os.environ.get('REFERENCE_COLLECTOR_MODES')
    if selected:
        modes = selected.split(',')
    with tempfile.TemporaryDirectory(prefix='guardian-reference-collector-') as temporary:
        temporary = Path(temporary)
        script = temporary / 'reference-fixture.ps1'
        script.write_text(accelerated, encoding='utf-8-sig')
        for mode in modes:
            case = temporary / mode
            (case / 'remote/files').mkdir(parents=True)
            (case / 'bin').mkdir()
            project = case / 'app with spaces'
            project.mkdir()
            (project / 'package.json').write_text('{}')
            adb = case / 'bin/adb'
            adb.write_text(source)
            adb.chmod(0o755)
            # Restore both an existing setting and settings absent on entry.
            previous = case / 'remote/files/guardian-voice-processing.txt'
            previous.write_bytes(b'ORIGINAL\n')
            env = dict(os.environ, PATH=str(case / 'bin') + ':' + os.environ['PATH'],
                       FAKE_ADB_ROOT=str(case / 'remote'), FAKE_MODE=mode, FAKE_FIRERED='5')
            for key in ['XDG_CACHE_HOME', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME']:
                env[key] = str(case / key)
                Path(env[key]).mkdir()
            command = [os.environ.get('PWSH', 'pwsh'), '-NoProfile', '-File', str(script),
                       '-ProjectDirectory', str(project), '-PackageName', 'com.example.test',
                       '-DurationSeconds', '1', '-TestCase', 'FREE',
                       '-ProcessingMode', 'REFERENCE_NOISE_STUDY']
            run = subprocess.run(command, env=env, input='synthetic fixture\n\n\nOUI fixture\n',
                                 text=True, capture_output=True, timeout=60)
            expected_ok = mode in ['ok', 'mono']
            assert (run.returncode == 0) == expected_ok, mode + '\n' + run.stdout + run.stderr
            archives = list((project / 'diagnostics-voix').glob('*.zip'))
            assert len(archives) == 1, (mode, run.stdout, run.stderr)
            with zipfile.ZipFile(archives[0]) as archive:
                metadata = read_json(archive, 'session.json')
                assert metadata['completed'] == expected_ok and metadata['controlsRestored']
                assert metadata['collectorVersion'] == 'microphone-selection-3.5.2'
                assert 'asrMicrophoneSelections' in metadata
                assert 'asrMicrophoneQualities' in metadata
                assert 'asrInputIdentities' in metadata
                assert 'diagnosticDuplicateOffers' in metadata
                assert metadata['processingModeRequested'] == 'REFERENCE_NOISE_STUDY'
                assert archive.read('guardian.log') and archive.read('meminfo-before.txt')
                if mode == 'wrong-ready-role':
                    assert not metadata.get('referenceStartupConfirmed', False)
                    assert metadata['error']
                else:
                    assert metadata['referenceStartupConfirmed']
                    assert metadata['fsmnConfirmation']['startupConfirmed']
                    assert len([name for name in archive.namelist()
                                if name.startswith('wav/') and name.endswith('.wav')]) == 5
                    if mode == 'corrupt-copy':
                        assert 'Copie incorrecte' in metadata['error']
                    elif mode == 'pair-corrupt':
                        assert 'Paire reference differente' in metadata['error']
                    elif mode == 'missing-file':
                        assert 'Export reference manquant' in metadata['error']
                    else:
                        exports = metadata['referenceExports']
                        assert exports['byteExactPairs'] and not exports['syntheticPaddingAllowed']
                        assert metadata['referenceApplicable'] == (mode != 'mono')
                        assert exports['pairedWindows'] == (0 if mode == 'mono' else 2)
                        assert metadata['referenceNoiseUserReport'] == 'OUI fixture'
                        assert metadata['referenceNoiseProtocol']['verification'] == 'USER_REPORT_RECORDED_UNADJUDICATED'
                        assert metadata['referenceNoiseProtocol']['productionQualification'] is False
                        if mode == 'mono':
                            assert exports['fullFloatStreams'] == exports['pairedFloatStreams'] == 0
                            assert not read_csv(archive, 'reference-study/reference-files.csv')
                            assert metadata['studyValidationErrors'] == []
                        else:
                            assert_reference_audio(archive)
                            assert exports['fullFloatStreams'] == 4 and exports['pairedFloatStreams'] == 8
                            assert len(metadata['referenceWindows']) == 2
                            assert all(window['diagnosticForced'] and not window['productionEligible']
                                       and window['primaryOriginalAccepted'] is False
                                       and window['role'] == 'SHADOW_NO_ALERT'
                                       for window in metadata['referenceWindows'])
                            assert len(metadata['referenceDiagnosticResults']) == 2
                            assert archive.read('meminfo-after.txt')
                            if mode == 'wrong-session-role':
                                assert any('SESSION_START' in error for error in metadata['studyValidationErrors'])
                            else:
                                assert metadata['studyValidationErrors'] == []
                # Every manifest entry hashes the exact bytes actually archived.
                for record in read_json(archive, 'wav-manifest.json'):
                    name = record['file'] if '/' in record['file'] else 'wav/' + record['file']
                    assert hashlib.sha256(archive.read(name)).hexdigest() == record['sha256']
            assert previous.read_bytes() == b'ORIGINAL\n'
            assert sorted(path.name for path in (case / 'remote/files').glob('guardian-*.txt')) == [previous.name]
            print('PASS reference collector', mode, flush=True)
        print('PASS', len(modes), 'reference collector scenarios', flush=True)


if __name__ == '__main__':
    run_scenarios()
