"""Fixed exploratory rule: WebRTC AND (Silero OR TEN).
WebRTC mode1/gain4/support180ms and longest60ms. Silero gain16 and TEN gain64,
both probability .5/minSpeech128ms/support100ms. All gains are on confirmation
copies only. Original Qwen PCM is untouched. Completed-window CPU evaluation.
TEN license has extra conditions; no TEN model is redistributed here.
"""
import argparse,importlib.util,json,time
from pathlib import Path
import numpy as np,sherpa_onnx as s,webrtcvad
spec=importlib.util.spec_from_file_location('base_eval',Path(__file__).resolve().parents[1]/'simulation-admission/evaluate.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def neural(model,family):
 c=s.VadModelConfig();m=c.silero_vad if family=='silero' else c.ten_vad;m.model=str(model);m.threshold=.5;m.min_speech_duration=.128;m.min_silence_duration=.128;m.max_speech_duration=30;m.window_size=512 if family=='silero' else 256;c.sample_rate=16000;c.num_threads=1
 return s.VoiceActivityDetector(c,buffer_size_in_seconds=40)

def decision(audio,sv,tv,neural_audio=None):
 w=webrtcvad.Vad(1);flags=[]
 for i in range(0,len(audio),480):
  x=audio[i:i+480]
  if len(x)<480:x=np.pad(x,(0,480-len(x)))
  pcm=np.round(np.clip(base.level(x,4),-1,32767/32768)*32768).astype('<i2').tobytes();flags.append(w.is_speech(pcm,16000))
 pos,longest=base.features(flags,.03);wa=pos+1e-8>=.18 and longest+1e-8>=.06
 ns=[]
 for v,gain in [(sv,16 if neural_audio is None else 1),(tv,64 if neural_audio is None else 1)]:
  v.reset();nf=[]
  neural_input=audio if neural_audio is None else neural_audio
  for i in range(0,len(neural_input),800):
   v.accept_waveform(base.level(neural_input[i:i+800],gain));nf.append(v.is_speech_detected())
   while not v.empty():v.pop()
  ns.append(sum(nf)*.05+1e-8>=.1)
 return dict(accepted=wa and any(ns),webrtc=wa,silero=ns[0],ten=ns[1])

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--silero',type=Path,required=True);p.add_argument('--ten',type=Path,required=True);p.add_argument('--stress',action='store_true');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if a.output.exists():p.error('Choose new output')
 data=[(row,base.read(Path(row['path']))) for row in json.loads(a.manifest.read_text())];sv=neural(a.silero,'silero');tv=neural(a.ten,'ten');results=[]
 for db in ([-6,0,6] if a.stress else [0]):
  for offset in ([0,160,320] if a.stress else [0]):
   start=time.monotonic();decisions=[]
   for row,audio in data:decisions.append(dict(id=row['id'],label=row['label'],condition=row['condition'],**decision(audio[offset:]*10**(db/20),sv,tv)))
   counts={label:{'accepted':sum(d['accepted'] for d in decisions if d['label']==label),'total':sum(d['label']==label for d in decisions)} for label in sorted({d['label'] for d in decisions})}
   result=dict(db=db,offsetMs=offset/16,counts=counts,decisions=decisions,wallSeconds=time.monotonic()-start);results.append(result);print(db,offset/16,counts,flush=True);a.output.write_text(json.dumps(results,indent=2))
if __name__=='__main__':main()
