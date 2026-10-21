"""Predeclared neural-copy RMS targets -40/-30/-20 dBFS; no Qwen gain change.
Peak bound .8 and gain bound512, no attenuation. WebRTC sees the original.
Runs the same nine perturbations for each target; this is exploration, not a
held-out validation set. TEN licensing must be checked before product use.
"""
import argparse,json,time
from pathlib import Path
import numpy as np
from evaluate_fusion import base,neural,decision

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--silero',type=Path,required=True);p.add_argument('--ten',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if a.output.exists():p.error('Choose new output')
 data=[(r,base.read(Path(r['path']))) for r in json.loads(a.manifest.read_text())];sv=neural(a.silero,'silero');tv=neural(a.ten,'ten');results=[]
 for target in [-40,-30,-20]:
  for db in [-6,0,6]:
   for offset in [0,160,320]:
    decisions=[];start=time.monotonic()
    for row,source in data:
     audio=source[offset:]*10**(db/20);rms=max(float(np.sqrt(np.mean(audio.astype(np.float64)**2))),1e-30);peak=max(float(np.max(abs(audio))),1e-30);gain=max(1,min(512,10**(target/20)/rms,.8/peak));neural_copy=(audio*gain).astype(np.float32)
     decisions.append(dict(id=row['id'],label=row['label'],condition=row['condition'],**decision(audio,sv,tv,neural_copy)))
    counts={label:{'accepted':sum(d['accepted'] for d in decisions if d['label']==label),'total':sum(d['label']==label for d in decisions)} for label in sorted({d['label'] for d in decisions})}
    result=dict(targetDbfs=target,db=db,offsetMs=offset/16,counts=counts,decisions=decisions,wallSeconds=time.monotonic()-start);results.append(result);print(target,db,offset/16,counts,flush=True);a.output.write_text(json.dumps(results,indent=2))
if __name__=='__main__':main()
