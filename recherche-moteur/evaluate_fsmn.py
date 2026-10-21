"""CPU FSMN confirmation benchmark. Requires pinned lovemefan/fsmn-vad checkout.
Outputs contain opaque clip identifiers and decisions only, no audio or paths.
This measures acoustic admission, not ASR or emergency alerts.
"""
import argparse,hashlib,json,sys,time,wave
from pathlib import Path
import numpy as np

def read(p):
 if p.suffix=='.f32': return np.fromfile(p,dtype='<f4')
 with wave.open(str(p)) as w:
  assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,2,16000)
  return np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--stress',action='store_true');p.add_argument('--fixed',action='store_true');p.add_argument('--threshold',type=float,default=.6);a=p.parse_args()
 if a.output.exists():p.error('Output exists')
 sys.path.insert(0,str(a.source))
 import onnxruntime as ort
 from fsmnvad import FSMNVad
 # Bound CPU threads, preserving upstream inference/feature/postprocessing code.
 original=ort.InferenceSession
 import importlib
 runtime=importlib.import_module('fsmnvad.runtime.src.fsmnvad.VadOrtInferSession')
 def session(*args,**kwargs):
  kwargs['sess_options'].intra_op_num_threads=1;kwargs['sess_options'].inter_op_num_threads=1
  return original(*args,**kwargs)
 runtime.InferenceSession=session
 config=a.source/'fsmnvad/config/config.yaml';rows=json.loads(a.manifest.read_text());data=[(r,read(Path(r['path']))) for r in rows];results=[]
 variants=[(g,t,0,0) for g in [1,4,16,64] for t in [.4,.6,.8]] if not a.stress else [(64,t,db,offset) for t in [.4,.6,.8] for db in [-6,0,6] for offset in [0,5,10,15,20]]
 if a.fixed:variants=[(64,a.threshold,0,0)]
 for gain,threshold,db,offset in variants:
  vad=FSMNVad(config,level='error');vad.vad.vad_opts.speech_noise_thres=threshold;vad.vad.all_reset_detection();assert vad.vad.speech_noise_thres==threshold
  decisions=[];start=time.monotonic()
  for row,source in data:
   x=source.copy()*10**(db/20)
   if offset:x=x[int(offset*16):]
   x=x*max(1.,min(gain,.8/max(float(np.max(np.abs(x))),1e-30)))
   feats,_=vad.extract_feature(x);segments,_=vad.vad.infer_offline(feats[None,...],x[None,...],in_cache={},is_final=True)
   flat=[s for batch in segments for s in batch]
   assert all(len(s)==2 and 0<=s[0]<s[1]<=len(x)/16+30 for s in flat),flat
   decisions.append(dict(id=row['id'],label=row['label'],accepted=bool(flat),segmentsMs=flat))
  counts={label:dict(accepted=sum(d['accepted'] for d in decisions if d['label']==label),total=sum(d['label']==label for d in decisions)) for label in sorted({r['label'] for r in rows})}
  record=dict(gainMax=gain,speechNoiseThreshold=threshold,levelDb=db,offsetMs=offset,counts=counts,wallSeconds=time.monotonic()-start,decisions=decisions);results.append(record);print({k:v for k,v in record.items() if k!='decisions'},flush=True)
 models=list((a.source/'fsmnvad/runtime/onnx/vad/onnx').glob('*'))
 a.output.write_text(json.dumps(dict(scope='CPU completed-window admission, not Android, ASR or alerts',models={q.name:hashlib.sha256(q.read_bytes()).hexdigest() for q in models},results=results),indent=2))
if __name__=='__main__': main()
