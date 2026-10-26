"""Enhance actual completed stream candidates, including rejected windows.
No oracle text/window selection. Outputs and captions must stay private.
"""
import argparse,json,time
from pathlib import Path
import numpy as np
from prepare import deepfilter

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--windows',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--tools',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True);records=[];metrics=[]
 for w in json.loads(a.windows.read_text()):
  x=np.fromfile(w['path'],dtype='<f4')
  for attenuation,gain in [(18,16),(9,8)]:
   method=f'df3-g{gain}-a{attenuation}';folder=a.output/w['id']/method;folder.mkdir(parents=True,exist_ok=True);out=folder/'enhanced.f32';start=time.perf_counter();cached=out.exists()
   if cached:y=np.fromfile(out,dtype='<f4')
   else:
    y=deepfilter(x,a.tools,folder,gain,attenuation);assert len(y)==len(x) and np.isfinite(y).all();y.astype('<f4').tofile(out)
   records.append(dict(w,id=w['id']+'-'+method,path=str(out),method=method,family='df3-post'))
   metrics.append(dict(id=records[-1]['id'],cached=cached,seconds=time.perf_counter()-start,audioSeconds=len(x)/16000))
  print(w['id'],'ready',len(records),flush=True);(a.output/'manifest-private.json').write_text(json.dumps(records,indent=2));(a.output/'metrics.json').write_text(json.dumps(metrics,indent=2))
if __name__=='__main__':main()
