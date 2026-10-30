"""Run the v3.5.4 collector against synthetic ADB, including rejected proof cases."""
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import zipfile

import test_adaptive_collector_v353 as base

repo = Path(__file__).resolve().parents[3]
source = base.source.replace('guardian-voice-v3.5.3-adaptive-candidate', 'guardian-voice-v3.5.4-temporal-rescue')
source = re.sub(r'\b8000\b', '32000', source)
source = source.replace("if event=='SESSION_START':\n  kw['adaptiveTranscription']", "if event=='SESSION_START':\n  kw['temporalRescueEnabled']=mode!='rescue-off'\n  kw['adaptiveTranscription']")
source = source.replace("def emit(event,**kw):\n", """def emit(event,**kw):
 if event=='WAV_READY' and mode!='missing-ready':
  emit('TEMPORAL_RESCUE_READY',model='GTCRN_UNITY_CONFIRMATION',inputGain=1,asrAudio='CALIBRATED_REFERENCE_LONG',minimumIslandFrames=15,maximumGapFrames=120,minimumCorePositiveFrames=100,minimumCoreDensity=.25)
 if event=='ADAPTIVE_ADMISSION' and mode!='normal-admission':
  kw.update(temporalRescueAttempted=True,temporalRescueAccepted=mode!='bad-proof',filteredFsmnAccepted=False)
  confirmation='adaptive-rescue/abc12345/segment-1/'+prefix+'-window'
  for stage in ['truth','asr']:
   for extension in ['wav','f32']:
    src=f('files/guardian-diagnostics/'+relative+'-'+stage+'.'+extension)
    dst=f('files/guardian-diagnostics/'+confirmation+'-'+stage+'.'+extension)
    dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
  shutil.copyfile(f('files/guardian-diagnostics/'+relative+'-index.tsv'),f('files/guardian-diagnostics/'+confirmation+'-index.tsv'))
  emit('TEMPORAL_RESCUE_RESULT',segment=1,sourceStartSample=16000 if mode=='bad-window' else 0,
   sourceEndSample=48000 if mode=='bad-window' else 32000,sourceChannel=1 if mode=='bad-source' else 0,routeRevision=0,
   success=True,accepted=True,fireRedAccepted=True,fsmnAccepted=True,coherent=True,calibrationClean=True,
   corePositiveFrames=99 if mode=='bad-core' else 100,coreSpanFrames=150,coreStartFrame=10,coreEndFrameExclusive=160,
   coreDensity=.66 if mode=='bad-core' else .1 if mode=='bad-density' else (100/150),asrAudio='CALIBRATED_REFERENCE_LONG',
   confirmationExportPrefix=confirmation,confirmationPcmSha256='0'*64 if mode=='bad-confirmation-hash' else hashlib.sha256(data).hexdigest())
 if event=='FIRERED_RESULT' and mode!='normal-admission':kw['admissionReason']='CALIBRATED_FILTERED_TEMPORAL_RESCUE'
 if event=='ASR_INPUT':kw.update(startSample=0,endSample=32000,channel=0,source='CALIBRATED_REFERENCE_LONG')
 if event=='ASR_INPUT' and mode=='rescue-error':emit('TEMPORAL_RESCUE_ERROR',reason='SYNTHETIC_INFERENCE_FAILURE')
""", 1)


def main():
    collector = (repo/'enregistrer-guardian-adaptatif.ps1').read_text(encoding='utf-8-sig')
    collector = collector.replace('.AddSeconds(60)', '.AddSeconds(2)').replace('.AddSeconds(15)', '.AddSeconds(2)')
    modes = ['rescue','normal-admission','bad-core','bad-density','bad-window','bad-source',
             'bad-proof','bad-confirmation-hash','missing-ready','rescue-off','wrong-engine','rescue-error']
    selected = os.environ.get('TEMPORAL_COLLECTOR_MODES')
    if selected: modes = selected.split(',')
    with tempfile.TemporaryDirectory(prefix='guardian-temporal-collector-') as tmp:
        tmp=Path(tmp); script=tmp/'collector.ps1';script.write_text(collector,encoding='utf-8-sig')
        for mode in modes:
            case=tmp/mode;(case/'remote/files').mkdir(parents=True);(case/'bin').mkdir()
            project=case/'app with spaces';project.mkdir();(project/'package.json').write_text('{}')
            adb=case/'bin/adb';adb.write_text(source);adb.chmod(0o755)
            previous=case/'remote/files/guardian-voice-processing.txt';previous.write_bytes(b'ORIGINAL\n')
            env=dict(os.environ,PATH=str(case/'bin')+':'+os.environ['PATH'],
                FAKE_ADB_ROOT=str(case/'remote'),FAKE_MODE=mode,FAKE_FIRERED='5')
            for key in ['XDG_CACHE_HOME','XDG_CONFIG_HOME','XDG_DATA_HOME']:
                env[key]=str(case/key);Path(env[key]).mkdir()
            run=subprocess.run([os.environ.get('PWSH','pwsh'),'-NoProfile','-File',str(script),
                '-ProjectDirectory',str(project),'-PackageName','com.example.test','-DurationSeconds','1',
                '-TestCase','FREE','-ProcessingMode','ADAPTIVE_CANDIDATE',
                '-ExpectedEngineRevision','guardian-voice-v3.5.4-temporal-rescue','-RequireTemporalRescue'],
                input='synthetic fixture\n\n\n',text=True,capture_output=True,env=env,timeout=60)
            expected=mode in ['rescue','normal-admission']
            assert (run.returncode==0)==expected, mode+'\n'+run.stdout+run.stderr
            archives=list((project/'diagnostics-voix').glob('*.zip'));assert len(archives)==1
            with zipfile.ZipFile(archives[0]) as z:
                metadata=json.loads(z.read('session.json').decode('utf-8-sig'))
                assert metadata['controlsRestored'] and metadata['completed']==expected
                if mode=='rescue-error':
                    assert len(metadata['temporalRescueErrors'])==1
                    assert any('Secours temporel en erreur' in problem for problem in metadata['studyValidationErrors'])
                if expected:
                    assert metadata['temporalRescueRequired'] and metadata['fsmnAdmissionChecks'][0]['consistent']
                    rescued=mode=='rescue'
                    assert metadata['temporalRescuedWindows']==int(rescued)
                    assert metadata['temporalConfirmationWindowCount']==int(rescued)
                    if rescued:
                        proof=metadata['temporalRescueResults'][0]
                        pcm=z.read(proof['confirmationExportPrefix']+'-asr.f32')
                        assert hashlib.sha256(pcm).hexdigest()==proof['confirmationPcmSha256']
                        assert proof['confirmationExportPrefix'].startswith('adaptive-rescue/')
                for record in json.loads(z.read('wav-manifest.json').decode('utf-8-sig')):
                    name=record['file'] if '/' in record['file'] else 'wav/'+record['file']
                    assert hashlib.sha256(z.read(name)).hexdigest()==record['sha256']
            assert previous.read_bytes()==b'ORIGINAL\n'
            assert sorted(p.name for p in (case/'remote/files').glob('guardian-*.txt'))==[previous.name]
            print('PASS temporal rescue collector',mode,flush=True)


if __name__=='__main__':main()
