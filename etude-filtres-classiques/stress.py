"""Synthetic noise step only; attenuation is not a recognition/voice score."""
import argparse,json
from pathlib import Path
import numpy as np
from filters import apply,configs

def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args();rng=np.random.default_rng(304);x=rng.normal(0,.005,16000*12).astype(np.float32);x[16000*4:]*=4
 names={'original','wiener-sqrt-o1-f0.5','dd-a0.98-f0.5','adaptive-dd-a0.98-f0.5','adaptive-logmmse-a0.98-f0.5'};out=[]
 for c in configs():
  if c['name'] not in names:continue
  y=apply(x,c);stages={}
  for stage,start,end in [('known-noise',3.2,3.8),('just-after-step',4.2,4.8),('later',10,11.8)]:
   sl=slice(round(start*16000),round(end*16000));stages[stage]=float(20*np.log10(max(np.sqrt(np.mean(y[sl]**2))/np.sqrt(np.mean(x[sl]**2)),1e-20)))
  out.append(dict(method=c['name'],attenuationDb=stages,finite=bool(np.isfinite(y).all())))
 a.output.write_text(json.dumps(out,indent=2));print(json.dumps(out,indent=2))
if __name__=='__main__':main()
