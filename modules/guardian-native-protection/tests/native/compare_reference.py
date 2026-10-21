"""Compare the real deployed NCNN core against reference FireRed ONNX on private PCM.
Output is private per-window data; publish aggregated counts only.
"""
import argparse,importlib.util,json,subprocess,tempfile,types,sys,wave
from pathlib import Path
import numpy as np,onnxruntime as ort,kaldiio,kaldi_native_fbank as knf

def read(p):
 if p.suffix=='.f32':return np.fromfile(p,dtype='<f4')
 with wave.open(str(p)) as w:
  assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,2,16000)
  return np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ['source','onnx','cmvn','native-executable','assets','manifest','output']:p.add_argument('--'+key,type=Path,required=True)
 p.add_argument('--stress',action='store_true');a=p.parse_args()
 for name,path in [('fireredvad',a.source/'fireredvad'),('fireredvad.core',a.source/'fireredvad/core')]:
  module=types.ModuleType(name);module.__path__=[str(path)];sys.modules[name]=module
 spec=importlib.util.spec_from_file_location('fireredvad.core.vad_postprocessor',a.source/'fireredvad/core/vad_postprocessor.py');post=importlib.util.module_from_spec(spec);sys.modules[spec.name]=post;spec.loader.exec_module(post)
 opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1;ort_session=ort.InferenceSession(str(a.onnx),opts,providers=['CPUExecutionProvider']);stats=kaldiio.load_mat(str(a.cmvn));means=stats[0,:80]/stats[0,80];std=np.sqrt(np.maximum(stats[1,:80]/stats[0,80]-means**2,1e-20));rows=json.loads(a.manifest.read_text());results=[];maxfeat=maxprob=0.
 variants=[(db,offset) for db in [-6,0,6] for offset in [0,5,10,15,20]] if a.stress else [(0,0)]
 with tempfile.TemporaryDirectory() as tmp:
  t=Path(tmp)
  for db,offset in variants:
   decisions=[]
   for row in rows:
    original=read(Path(row['path']));x=original[offset*16:]*10**(db/20);x=x*max(1.,min(64,.8/max(float(np.max(abs(x))),1e-30)));x.astype('<f4').tofile(t/'pcm')
    subprocess.run([str(a.native_executable),str(a.assets/'firered_vad_non_stream.ncnn.param'),str(a.assets/'firered_vad_non_stream.ncnn.bin'),str(a.assets/'cmvn.bin'),str(t/'pcm'),str(t/'prob'),str(t/'feat')],check=True)
    opt=knf.FbankOptions();opt.frame_opts.samp_freq=16000;opt.frame_opts.frame_length_ms=25;opt.frame_opts.frame_shift_ms=10;opt.frame_opts.dither=0;opt.frame_opts.snip_edges=True;opt.mel_opts.num_bins=80;opt.mel_opts.debug_mel=False;fb=knf.OnlineFbank(opt);pcm=np.round(np.clip(x,-1,32767/32768)*32768).astype(np.int16).astype(np.float32);fb.accept_waveform(16000,pcm.tolist());features=((np.vstack([fb.get_frame(i) for i in range(fb.num_frames_ready)])-means)/std).astype(np.float32)
    nativefeat=np.fromfile(t/'feat',dtype='<f4').reshape(-1,80);ref=ort_session.run(None,{'feat':features[None]})[0].reshape(-1);native=np.fromfile(t/'prob',dtype='<f4');assert ref.shape==native.shape
    featdiff=float(np.max(abs(features-nativefeat)));probdiff=float(np.max(abs(ref-native)));maxfeat=max(maxfeat,featdiff);maxprob=max(maxprob,probdiff)
    accepted=[]
    for probs in [ref,native]:
     pp=post.VadPostprocessor(5,.5,20,2000,20,0,0);accepted.append(bool(pp.decision_to_segment(pp.process(probs.tolist()))))
    decisions.append(dict(id=row['id'],label=row['label'],reference=accepted[0],native=accepted[1],featureMaxError=featdiff,probabilityMaxError=probdiff))
   counts={l:dict(referenceAccepted=sum(d['reference'] for d in decisions if d['label']==l),nativeAccepted=sum(d['native'] for d in decisions if d['label']==l),total=sum(d['label']==l for d in decisions)) for l in sorted({r['label'] for r in rows})};results.append(dict(db=db,offsetMs=offset,counts=counts,decisions=decisions));print(db,offset,counts,flush=True)
 a.output.write_text(json.dumps(dict(featureMaxError=maxfeat,probabilityMaxError=maxprob,decisionMismatches=sum(d['native']!=d['reference'] for r in results for d in r['decisions']),results=results),indent=2));print('Maximum feature/probability errors',maxfeat,maxprob,flush=True)
if __name__=='__main__': main()
