"""Local research: FireRed Stream-VAD official ONNX + Earshot.
Reads the private completed-window manifest used in previous simulations.
No audio upload, Android modification, transcription scoring or alerts.
FireRed uses official Kaldi FBank/CMVN conventions and its postprocessor.
"""
import argparse,importlib.util,json,sys,time
from pathlib import Path
import numpy as np,onnxruntime as ort,kaldi_native_fbank as knf,kaldiio,earshot
spec=importlib.util.spec_from_file_location('base_eval',Path(__file__).resolve().parents[1]/'simulation-admission/evaluate.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def frontend(audio,stats):
 opts=knf.FbankOptions();opts.frame_opts.samp_freq=16000;opts.frame_opts.frame_length_ms=25;opts.frame_opts.frame_shift_ms=10;opts.frame_opts.dither=0;opts.frame_opts.snip_edges=True;opts.mel_opts.num_bins=80;opts.mel_opts.debug_mel=False
 pcm=np.round(np.clip(audio,-1,32767/32768)*32768).astype(np.int16);fb=knf.OnlineFbank(opts);fb.accept_waveform(16000,pcm.astype(np.float32).tolist())
 x=np.vstack([fb.get_frame(i) for i in range(fb.num_frames_ready)]) if fb.num_frames_ready else np.zeros((0,80),dtype=np.float32)
 means=stats[0,:80]/stats[0,80];var=np.maximum(stats[1,:80]/stats[0,80]-means**2,1e-20)
 return ((x-means)/np.sqrt(var)).astype(np.float32)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--model',type=Path,required=True);p.add_argument('--cmvn',type=Path,required=True);p.add_argument('--postprocessor',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--fire-gains',type=int,nargs='+',default=[1,4]);p.add_argument('--earshot-gains',type=int,nargs='+',default=[1,4,16]);a=p.parse_args()
 if any(g<1 for g in a.fire_gains+a.earshot_gains):p.error('Gain maximum must be >=1')
 if a.output.exists():p.error('Choose a new output JSON')
 spec=importlib.util.spec_from_file_location('firered_official_post',a.postprocessor);post=importlib.util.module_from_spec(spec);sys.modules[spec.name]=post;spec.loader.exec_module(post)
 opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1;session=ort.InferenceSession(str(a.model),opts,providers=['CPUExecutionProvider']);stats=kaldiio.load_mat(str(a.cmvn));data=[(r,base.read(Path(r['path']))) for r in json.loads(a.manifest.read_text())];results=[];cache_shape=(8,1,128,19);equivalence=None
 for gain in a.fire_gains:
  start=time.monotonic();traces=[]
  for row,audio in data:
   x=frontend(base.level(audio,gain),stats);cache=np.zeros(cache_shape,dtype=np.float32);probs=[]
   for i in range(0,len(x),16):
    out,cache=session.run(None,{'feat':x[None,i:i+16,:],'caches_in':cache});probs.extend(out.reshape(-1).tolist())
   if equivalence is None and len(x):
    ref_cache=np.zeros(cache_shape,dtype=np.float32);ref=[]
    for frame in x:
     out,ref_cache=session.run(None,{'feat':frame[None,None,:],'caches_in':ref_cache});ref.extend(out.reshape(-1).tolist())
    equivalence=float(np.max(abs(np.array(probs)-np.array(ref))));assert equivalence<1e-4,'Chunk/frame inference mismatch'
   traces.append((row,probs))
  for threshold in [.4,.5]:
   decisions=[]
   for row,probs in traces:
    pp=post.StreamVadPostprocessor(5,threshold,5,8,2000,20);events=[pp.process_one_frame(float(v)) for v in probs];accepted=any(e.is_speech_start for e in events);decisions.append(dict(id=row['id'],label=row['label'],condition=row['condition'],accepted=accepted,peakProbability=max(probs,default=0)))
   counts={label:{'accepted':sum(d['accepted'] for d in decisions if d['label']==label),'total':sum(d['label']==label for d in decisions)} for label in sorted({r['label'] for r,_ in data})};record=dict(candidate=f'FireRed-stream-threshold{threshold}-gainMax{gain}',counts=counts,decisions=decisions,analysisWallSeconds=time.monotonic()-start);results.append(record);print(record['candidate'],counts,flush=True)
 for gain in a.earshot_gains:
  traces=[];start=time.monotonic()
  for row,audio in data:
   d=earshot.Detector();scores=[]
   for i in range(0,len(audio),256):
    frame=audio[i:i+256]
    if len(frame)<256:frame=np.pad(frame,(0,256-len(frame)))
    scores.append(float(d.predict_f32(base.level(frame,gain).astype(np.float32))))
   traces.append((row,scores))
  for support in [.08,.16,.24]:
   decisions=[]
   for row,scores in traces:
    positive,longest=base.features([v>=.5 for v in scores],.016);decisions.append(dict(id=row['id'],label=row['label'],condition=row['condition'],accepted=positive+1e-8>=support and longest+1e-8>=.064,positiveSeconds=positive,longestSeconds=longest))
   counts={label:{'accepted':sum(d['accepted'] for d in decisions if d['label']==label),'total':sum(d['label']==label for d in decisions)} for label in sorted({r['label'] for r,_ in data})};record=dict(candidate=f'Earshot-threshold0.5-gainMax{gain}-support{support}',counts=counts,decisions=decisions,analysisWallSeconds=time.monotonic()-start);results.append(record);print(record['candidate'],counts,flush=True)
 a.output.write_text(json.dumps({'results':results,'fireRedFrameChunkMaxDifference':equivalence,'earshotVersion':earshot.__version__},indent=2))
if __name__=='__main__':main()
