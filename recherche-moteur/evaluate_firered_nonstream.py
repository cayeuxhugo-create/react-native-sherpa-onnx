"""FireRed non-stream VAD official ONNX, official frontend and postprocessor.
Completed-window confirmation only. No Android changes or ASR scoring.
"""
import argparse,importlib.util,json,sys,types,time
from pathlib import Path
import numpy as np,onnxruntime as ort,kaldiio
from evaluate_new_vad import base,frontend

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--model',type=Path,required=True);p.add_argument('--cmvn',type=Path,required=True);p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if a.output.exists():p.error('Choose a new output')
 # Load only the upstream pure-Python postprocessor, without importing its
 # PyTorch inference package; actual model inference below uses ONNX Runtime.
 for name,path in [('fireredvad',a.source/'fireredvad'),('fireredvad.core',a.source/'fireredvad/core')]:
  module=types.ModuleType(name);module.__path__=[str(path)];sys.modules[name]=module
 spec=importlib.util.spec_from_file_location('fireredvad.core.vad_postprocessor',a.source/'fireredvad/core/vad_postprocessor.py');post=importlib.util.module_from_spec(spec);sys.modules[spec.name]=post;spec.loader.exec_module(post)
 opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1;session=ort.InferenceSession(str(a.model),opts,providers=['CPUExecutionProvider']);stats=kaldiio.load_mat(str(a.cmvn));rows=json.loads(a.manifest.read_text());results=[]
 for gain in [1,4,16,64]:
  traces=[];start=time.monotonic()
  for row in rows:
   x=frontend(base.level(base.read(Path(row['path'])),gain),stats);probs=session.run(None,{'feat':x[None]})[0].reshape(-1).tolist();traces.append((row,probs))
  for threshold in [.4,.5]:
   decisions=[]
   for row,probs in traces:
    pp=post.VadPostprocessor(5,threshold,20,2000,20,0,0);intervals=pp.decision_to_segment(pp.process(probs));decisions.append(dict(id=row['id'],label=row['label'],accepted=bool(intervals)))
   counts={label:{'accepted':sum(d['accepted'] for d in decisions if d['label']==label),'total':sum(d['label']==label for d in decisions)} for label in sorted({r['label'] for r in rows})};record=dict(candidate=f'FireRed-nonstream-threshold{threshold}-gainMax{gain}',counts=counts,decisions=decisions,analysisWallSeconds=time.monotonic()-start);results.append(record);print(record['candidate'],counts,flush=True)
 a.output.write_text(json.dumps({'results':results},indent=2))
if __name__=='__main__':main()
