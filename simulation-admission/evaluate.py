"""Shadow acoustic confirmation on completed original-PCM segments.
No microphone, Android changes or alerts. JSON manifest rows: id, label,
condition, path; labels: negative / positive / short-word. .f32 is mono float32
16 kHz; .wav must be mono PCM16 16 kHz. Outputs contain no audio or paths.
This measures admission, not word recognition or end-to-end false alerts.
"""
import argparse,hashlib,json,time,wave
from pathlib import Path
import numpy as np
import sherpa_onnx as sherpa
import webrtcvad

def read(path):
 if path.suffix=='.f32':return np.fromfile(path,dtype='<f4')
 with wave.open(str(path)) as w:
  if (w.getnchannels(),w.getsampwidth(),w.getframerate())!=(1,2,16000):raise ValueError('Expected mono PCM16 16000 Hz')
  return np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768

def level(x,maximum):
 return x*max(1.,min(maximum,.8/max(float(np.max(np.abs(x))),1e-30)))

def features(flags,step):
 longest=run=0
 for f in flags:
  run=run+1 if f else 0;longest=max(longest,run)
 return sum(flags)*step,longest*step

def summarize(name,records,rule,wall):
 decisions=[dict(id=r['id'],label=r['label'],condition=r['condition'],accepted=rule(r),positiveSeconds=r['positiveSeconds'],longestSeconds=r['longestSeconds']) for r in records]
 groups={}
 for label in ['negative','positive','short-word']:
  rows=[d for d in decisions if d['label']==label];groups[label]={'accepted':sum(d['accepted'] for d in rows),'total':len(rows)}
 return dict(candidate=name,counts=groups,analysisWallSeconds=wall,decisions=decisions)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--silero',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--family',choices=['webrtc','silero'],required=True)
 a=p.parse_args()
 if a.output.exists():p.error('Choose a new output JSON')
 rows=json.loads(a.manifest.read_text());dataset=[];hashes={}
 for row in rows:
  if row['label'] not in ['positive','negative','short-word']:raise ValueError('Unknown label')
  path=Path(row['path']);audio=read(path)
  if not len(audio) or not np.isfinite(audio).all():raise ValueError('Empty or nonfinite audio')
  dataset.append((row,audio));hashes[row['id']]=hashlib.sha256(path.read_bytes()).hexdigest()
 results=[]
 if a.family=='webrtc':
  for mode in range(4):
   for gain in [1,4,16]:
    start=time.monotonic();records=[]
    for row,audio in dataset:
     v=webrtcvad.Vad(mode);flags=[]
     for i in range(0,len(audio),480):
      x=audio[i:i+480]
      if len(x)<480:x=np.pad(x,(0,480-len(x)))
      pcm=np.round(np.clip(level(x,gain),-1,32767/32768)*32768).astype('<i2').tobytes();flags.append(v.is_speech(pcm,16000))
     positive,longest=features(flags,.03);records.append(dict(row,positiveSeconds=positive,longestSeconds=longest))
    wall=time.monotonic()-start
    for required in [.09,.18,.30]:
     name=f'WebRTC-mode{mode}-gain{gain}-support{required}'
     results.append(summarize(name,records,lambda r:r['positiveSeconds']+1e-8>=required and r['longestSeconds']+1e-8>=.06,wall))
 else:
  for threshold in [.5,.65,.8]:
   for duration in [.192,.32]:
    for gain in [1,4]:
     config=sherpa.VadModelConfig();config.silero_vad.model=str(a.silero);config.silero_vad.threshold=threshold;config.silero_vad.min_speech_duration=duration;config.silero_vad.min_silence_duration=.128;config.silero_vad.max_speech_duration=30;config.silero_vad.window_size=512;config.sample_rate=16000;config.num_threads=1
     v=sherpa.VoiceActivityDetector(config,buffer_size_in_seconds=40);records=[];start=time.monotonic()
     for row,audio in dataset:
      v.reset();flags=[]
      for i in range(0,len(audio),800):
       x=audio[i:i+800];v.accept_waveform(level(x,gain));flags.append(v.is_speech_detected())
       while not v.empty():v.pop()
      positive,longest=features(flags,.05);records.append(dict(row,positiveSeconds=positive,longestSeconds=longest))
     results.append(summarize(f'Silero-threshold{threshold}-duration{duration}-gain{gain}',records,lambda r:r['positiveSeconds']+1e-8>=.1,time.monotonic()-start))
 output={'family':a.family,'sherpaVersion':sherpa.__version__,'inputHashes':hashes,'sileroSha256':hashlib.sha256(a.silero.read_bytes()).hexdigest(),'scope':'Completed-window acoustic confirmation on CPU; no Android or alert validation','results':results}
 a.output.write_text(json.dumps(output,ensure_ascii=False,indent=2))
 for result in results:print(result['candidate'],json.dumps(result['counts']),flush=True)
if __name__=='__main__':main()
