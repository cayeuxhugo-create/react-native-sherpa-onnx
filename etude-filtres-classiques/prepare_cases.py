"""Apply frozen configurations to full recordings, slice actual candidate intervals.
Screen: 3 noisy speech + 5 recent negatives. Validation: remaining controls.
All paths, samples and transcripts in --output must remain private.
"""
import argparse,json,time
from pathlib import Path
import numpy as np
from filters import configs,apply

def main():
 p=argparse.ArgumentParser();p.add_argument('--corpus',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--phase',choices=['screen','validate'],required=True);p.add_argument('--selected',type=Path);a=p.parse_args()
 if a.output.exists():p.error('Use a fresh output directory')
 a.output.mkdir(parents=True);cs=configs()
 if a.selected:
  names=json.loads(a.selected.read_text());cs=[c for c in cs if c['name'] in names];assert len(cs)==len(names)
 (a.output/'configs.json').write_text(json.dumps(cs,indent=2));out=[];metrics=[]
 for d in json.loads(a.corpus.read_text()):
  windows=[w for w in d['windows'] if (d['scope']=='recent' and (d['id']=='vacuum3m' or w['group']=='negative'))==(a.phase=='screen')]
  if not windows:continue
  x=np.fromfile(d['path'],dtype='<f4')
  for c in cs:
   folder=a.output/d['id']/c['name'];folder.mkdir(parents=True);start=time.perf_counter();y=apply(x,c)
   assert len(x)==len(y) and y.dtype==np.float32 and np.isfinite(y).all()
   y.astype('<f4').tofile(folder/'full.f32')
   metrics.append(dict(dataset=d['id'],method=c['name'],seconds=time.perf_counter()-start,audioSeconds=len(x)/16000,peak=float(np.max(abs(y)))))
   for w in windows:
    path=folder/f"segment-{w['segment']}.f32";y[w['start']:w['end']].astype('<f4').tofile(path)
    out.append(dict(w,id=f"{d['id']}-{c['name']}-{w['segment']}",path=str(path),dataset=d['id'],scope=d['scope'],method=c['name'],family=c['family']))
   print(d['id'],c['name'],'prepared',len(out),flush=True)
   (a.output/'manifest-private.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));(a.output/'metrics.json').write_text(json.dumps(metrics,indent=2))
 (a.output/'confirm.tsv').write_text(''.join(e['id']+'\t'+e['group']+'\t'+e['path']+'\n' for e in out))
 # Split by complete methods to run independent ASR workers with bounded resources.
 for k in [0,1]:
  names={c['name'] for i,c in enumerate(cs) if i%2==k};(a.output/f'manifest-{k}-private.json').write_text(json.dumps([e for e in out if e['method'] in names],ensure_ascii=False,indent=2))
if __name__=='__main__':main()
