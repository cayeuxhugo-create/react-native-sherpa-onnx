"""Real PowerShell collector with synthetic ADB, no phone or private corpus."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

import test_microphone_collector as base

repo = Path(__file__).resolve().parents[3]
source = base.source.replace('guardian-voice-v3.5.2-microphone-selection',
                             'guardian-voice-v3.5.3-adaptive-candidate')
source = source.replace("if event=='SESSION_START':\n  kw.update(",
                        "if event=='SESSION_START':\n  kw['adaptiveTranscription']=mode!='mode-off'\n  kw.update(")
source = source.replace("!='REFERENCE_NOISE_STUDY':", " not in ['REFERENCE_NOISE_STUDY','ADAPTIVE_CANDIDATE']:")
source = source.replace("channels=1 if mode=='mono' else 2", "channels=1 if mode=='mono' else 4 if mode=='four-channels' else 2")
source = source.replace("for channel in range(2):", "for channel in range(4 if mode=='four-channels' else 2):")
needle = "  if mode=='missing-file':"
assert needle in source
source = source.replace(needle, """  relative='adaptive-asr/abc12345/segment-1/'+prefix
  data=f('files/guardian-diagnostics/'+prefix+'-asr.f32').read_bytes()[:8000*4]
  for stage in ['truth','asr']:
   target=f('files/guardian-diagnostics/'+relative+'-'+stage+'.f32')
   target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
   values=struct.unpack('<'+'f'*8000,data)
   with wave.open(str(target.with_suffix('.wav')),'wb') as w:
    w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000)
    w.writeframes(struct.pack('<'+'h'*8000,*(round(v*32768) for v in values)))
  f('files/guardian-diagnostics/'+relative+'-index.tsv').write_text('stage\\tsequence\\tcapturedAtNs\\tfileSampleStart\\tsampleCount\\nasr\\t1\\t0\\t0\\t8000\\n')
  emit('FSMN_RESULT',segment=1,model='FSMN_OFFLINE_NCNN_FP32',source='PRIMARY_ORIGINAL_PCM',evaluated=False,success=False,accepted=False,reason='SKIPPED_ORIGINAL_REJECT')
  emit('ADAPTIVE_ADMISSION',segment=1,originalAccepted=False,filteredAvailable=True,calibrationClean=True,filteredFireRedAccepted=True,filteredFsmnAccepted=mode!='bad-proof',usedFiltered=True,accepted=True)
  emit('FIRERED_RESULT',segment=1,accepted=True,originalAccepted=False,fsmnConfirmationRequired=True,fsmnAccepted=True,admissionReason='CALIBRATED_FILTERED_DUAL_NEURAL')
  emit('ASR_INPUT',segment=1,samples=8000,windowExportPrefix=relative,pcmSha256='0'*64 if mode=='wrong-identity' else hashlib.sha256(data).hexdigest())
  if mode=='missing-file':""")


def main():
    collector = (repo/'enregistrer-guardian-adaptatif.ps1').read_text(encoding='utf-8-sig')
    collector = collector.replace('.AddSeconds(60)', '.AddSeconds(2)').replace('.AddSeconds(15)', '.AddSeconds(2)')
    with tempfile.TemporaryDirectory(prefix='guardian-adaptive-collector-') as tmp:
        tmp = Path(tmp)
        script=tmp/'collector.ps1'; script.write_text(collector,encoding='utf-8-sig')
        modes=['ok','four-channels','wrong-identity','bad-proof','wrong-engine','mode-off']
        for mode in modes:
            case=tmp/mode; (case/'remote/files').mkdir(parents=True);(case/'bin').mkdir()
            project=case/'app with spaces';project.mkdir();(project/'package.json').write_text('{}')
            adb=case/'bin/adb';adb.write_text(source);adb.chmod(0o755)
            previous=case/'remote/files/guardian-voice-processing.txt';previous.write_bytes(b'ORIGINAL\n')
            env=dict(os.environ,PATH=str(case/'bin')+':'+os.environ['PATH'],
                     FAKE_ADB_ROOT=str(case/'remote'),FAKE_MODE=mode,FAKE_FIRERED='5')
            for key in ['XDG_CACHE_HOME','XDG_CONFIG_HOME','XDG_DATA_HOME']:
                env[key]=str(case/key);Path(env[key]).mkdir()
            command=[os.environ.get('PWSH','pwsh'),'-NoProfile','-File',str(script),
                '-ProjectDirectory',str(project),'-PackageName','com.example.test',
                '-DurationSeconds','1','-TestCase','FREE','-ProcessingMode','ADAPTIVE_CANDIDATE']
            if mode=='four-channels': command+=['-CaptureRoute','MIC_STEREO_FOCUS']
            run=subprocess.run(command,
                input='synthetic fixture\n\n\n',text=True,capture_output=True,env=env,timeout=60)
            expected=mode in ['ok','four-channels']
            assert (run.returncode==0)==expected, mode+'\n'+run.stdout+run.stderr
            archives=list((project/'diagnostics-voix').glob('*.zip'));assert len(archives)==1
            with zipfile.ZipFile(archives[0]) as z:
                metadata=json.loads(z.read('session.json').decode('utf-8-sig'))
                assert metadata['controlsRestored'] and metadata['completed']==expected
                if expected:
                    assert metadata['adaptiveAsrWindowCount']==1
                    assert metadata['fsmnAdmissionChecks'][0]['consistent']
                    assert len(metadata['adaptiveAdmissions'])==1
                    inputs=metadata['asrInputIdentities'];assert len(inputs)==1
                    pcm=z.read(inputs[0]['windowExportPrefix']+'-asr.f32')
                    assert hashlib.sha256(pcm).hexdigest()==inputs[0]['pcmSha256']
                for record in json.loads(z.read('wav-manifest.json').decode('utf-8-sig')):
                    name=record['file'] if '/' in record['file'] else 'wav/'+record['file']
                    assert hashlib.sha256(z.read(name)).hexdigest()==record['sha256']
            assert previous.read_bytes()==b'ORIGINAL\n'
            assert sorted(p.name for p in (case/'remote/files').glob('guardian-*.txt'))==[previous.name]
            print('PASS adaptive collector',mode,flush=True)
        print('PASS 6 adaptive collector scenarios',flush=True)


if __name__=='__main__':main()
