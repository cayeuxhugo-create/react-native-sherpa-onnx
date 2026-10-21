"""Fixed FireRed non-stream confirmation: threshold .5, minSpeech200ms,
max gain64 on the confirmation copy, smooth5. No change of Qwen PCM.
Tests nine amplitude/framing variants when --stress is supplied.
"""
import argparse,importlib.util,json,sys,types,time
from pathlib import Path
import numpy as np,onnxruntime as ort,kaldiio
from evaluate_new_vad import base,frontend

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--model',type=Path,required=True);p.add_argument('--cmvn',type=Path,required=True);p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--stress',action='store_true');p.add_argument('--offset-samples',type=int,nargs='+',default=[0,160,320]);a=p.parse_args()
 if any(n<0 for n in a.offset_samples):p.error('Offsets must be nonnegative')
 if a.output.exists():p.error('Choose new output')
 for name,path in [('fireredvad',a.source/'fireredvad'),('fireredvad.core',a.source/'fireredvad/core')]:
  module=types.ModuleType(name);module.__path__=[str(path)];sys.modules[name]=module
 spec=importlib.util.spec_from_file_location('fireredvad.core.vad_postprocessor',a.source/'fireredvad/core/vad_postprocessor.py');post=importlib.util.module_from_spec(spec);sys.modules[spec.name]=post;spec.loader.exec_module(post)
 opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1;session=ort.InferenceSession(str(a.model),opts,providers=['CPUExecutionProvider']);stats=kaldiio.load_mat(str(a.cmvn));rows=[(row,base.read(Path(row['path']))) for row in json.loads(a.manifest.read_text())];results=[]
 for db in ([-6,0,6] if a.stress else [0]):
  for offset in (a.offset_samples if a.stress else [0]):
   start=time.monotonic();decisions=[]
   for row,audio in rows:
    x=frontend(base.level(audio[offset:]*10**(db/20),64),stats);probs=session.run(None,{'feat':x[None]})[0].reshape(-1).tolist();pp=post.VadPostprocessor(5,.5,20,2000,20,0,0);segments=pp.decision_to_segment(pp.process(probs));decisions.append(dict(id=row['id'],label=row['label'],condition=row['condition'],accepted=bool(segments)))
   counts={label:{'accepted':sum(d['accepted'] for d in decisions if d['label']==label),'total':sum(d['label']==label for d in decisions)} for label in sorted({r['label'] for r,_ in rows})};record=dict(db=db,offsetMs=offset/16,counts=counts,decisions=decisions,analysisWallSeconds=time.monotonic()-start);results.append(record);print(db,offset/16,counts,flush=True);a.output.write_text(json.dumps(results,indent=2))
if __name__=='__main__':main()
