#!/usr/bin/env python3
import sys,os,pathlib,json,time,hashlib,shutil,wave
r=pathlib.Path(os.environ['FAKE_ADB_ROOT']);a=sys.argv[1:];mode=os.environ.get('FAKE_MODE','ok')
def f(n):return r/n.lstrip('/')
def emit(event,**kw):
 if os.environ.get('FAKE_FIRERED') in ['1','2']:
  if event=='SESSION_START':kw['engineRevision']='guardian-voice-v3.2.1' if mode=='wrong-engine' else ('guardian-voice-v3.4-firered-stream' if os.environ.get('FAKE_FIRERED')=='2' else 'guardian-voice-v3.3-firered')
  if event=='WAV_READY':emit('FIRERED_START_FAILED' if mode=='firered-failed' else 'FIRERED_READY',**{k:v for k,v in kw.items() if k=='session'})
 with f('events').open('a') as h:h.write('10-19 12:00:00.000 123 123 I GuardianVoiceDiag: '+json.dumps(dict(event=event,**({'session':'abc12345'}|kw)))+'\n')
if a[0]=='get-state':print('device')
elif a[0]=='push':p=f(a[2]);p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(a[1],p);print('pushed')
elif a[0]=='logcat':
 pos=0
 while True:
  if f('events').exists():
   s=f('events').read_text();sys.stdout.write(s[pos:]);sys.stdout.flush();pos=len(s)
  time.sleep(.05)
elif a[0]=='exec-out':
 b=f(a[-1]).read_bytes()
 if mode=='corrupt' and a[-1].endswith('asr.wav'):b=b[:-1]+bytes([b[-1]^255])
 sys.stdout.buffer.write(b)
elif a[:2]==['shell','getprop']:print('Fixture' if 'model' in a[-1] else '16')
elif a[:2]==['shell','chmod']:pass
elif a[:2]==['shell','rm']:f(a[-1]).unlink(missing_ok=True)
elif a[:2]==['shell','log']:
 if a[-1].startswith('SIMPLE_RECORD_BEGIN') and mode=='restart':
  emit('SESSION_END',elapsedMs=100)
  emit('SESSION_START',session='other123',elapsedMs=101)
 if a[-1]=='SIMPLE_RECORD_END':
  for stage in (['asr']*5 if mode=='duplicate-saved' else ['raw','pregain','truth','asr','vad']):emit('WAV_SAVED',stage=stage)
  if mode=='limit':emit('WAV_LIMIT',stage='truth')
elif a[:2]==['shell','run-as']:
 c=a[3:]
 if c[0]=='id':print('uid=123')
 elif c[0]=='ls':print('\n'.join(p.name for p in f('files').iterdir()))
 elif c[0]=='sha256sum':print(hashlib.sha256(f(c[1]).read_bytes()).hexdigest()+'  '+c[1])
 elif c[0]=='rm':f(c[-1]).unlink(missing_ok=True)
 elif c[0]=='cp':
  dest=f(c[2]);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f(c[1]),dest)
  if c[2]=='files/guardian-voice-profile.txt' and f('files/guardian-record-wav.txt').read_text()=='ON' and not f('started').exists():
   f('started').touch();profile=dest.read_text();prefix='guardian-voice-1792424800000-'+profile+'-abc12345';source=f('files/guardian-audio-source.txt').read_text();multi=f('files/guardian-multimic.txt').read_text();channels=2 if multi=='VOICE_FOCUS' else 1
   if mode=='route-rejected':channels=1
   if mode=='ended-then-active':
    emit('SESSION_START',session='ended123',elapsedMs=1,diagnosticControlRevision=3,profile=profile,diagnosticsEnabledBy='PRIVATE_FILE',wavStageCount=5)
    emit('WAV_READY',session='ended123',prefix='guardian-voice-1792424800000-'+profile+'-ended123')
    emit('AUDIO_FRAME',session='ended123')
    emit('SESSION_END',session='ended123',elapsedMs=2)
   emit('SESSION_START',elapsedMs=3,diagnosticControlRevision=2 if mode=='old' else 3,profile=profile,diagnosticsEnabledBy='PRIVATE_FILE',wavStageCount=5)
   emit('CAPTURE_CONFIG',audioSource=1 if source=='MIC' else 6,channels=channels,gtcrnEnabled=True)
   emit('WAV_READY',prefix=prefix);emit('AUDIO_FRAME')
   for suffix in ['raw.wav','pregain.wav','truth.wav','asr.wav','vad.wav','index.tsv']:
    out=f('files/guardian-diagnostics/'+prefix+'-'+suffix);out.parent.mkdir(parents=True,exist_ok=True)
    if suffix.endswith('.wav'):
     with wave.open(str(out),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(bytes(range(256))*(10 if mode=='short-wav' else 250))
    else:out.write_text('stage\tsequence\tcapturedAtNs\tfileSampleStart\tsampleCount\nraw\t1\t20000\t0\t800\n')
 else:sys.exit('unknown '+repr(a))
else:sys.exit('unknown '+repr(a))
