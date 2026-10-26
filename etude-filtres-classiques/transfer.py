"""Secondary transfer on older noisy recordings, two fixed crops per phrase.
These are intelligibility diagnostics, not a new live segmentation score.
"""
import argparse,json,time
from pathlib import Path
import numpy as np
from filters import configs,apply

def main():
 p=argparse.ArgumentParser();p.add_argument('--datasets',type=Path,nargs='+',required=True);p.add_argument('--selected',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False);names=json.loads(a.selected.read_text());names=['original','wiener-legacy-o1-f0.5']+names[2:4];cs=[c for c in configs() if c['name'] in names];rows=[];datasets=[]
 for f in a.datasets:datasets+=json.loads(f.read_text())
 for d in datasets:
  x=np.fromfile(d['path'],dtype='<f4')
  for c in cs:
   folder=a.output/d['id']/c['name'];folder.mkdir(parents=True);y=apply(x,c)
   for n,(pos,text) in enumerate(zip(d['prompts'],d['texts']),1):
    for shift in [-.5,1.5]:
     begin=max(0,round((pos+shift)*16000));end=min(len(x),begin+128000);path=folder/f'p{n}-s{shift}.f32';y[begin:end].astype('<f4').tofile(path)
     rows.append(dict(id=f"{d['id']}-{c['name']}-p{n}-s{shift}",path=str(path),dataset=d['id'],method=c['name'],family=c['family'],segment=n,shift=shift,expected=text,group='speech',scope='transfer-guided'))
 (a.output/'manifest-private.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2));(a.output/'confirm.tsv').write_text(''.join(e['id']+'\t'+e['group']+'\t'+e['path']+'\n' for e in rows))
 for k in [0,1]:
  chosen={c['name'] for i,c in enumerate(cs) if i%2==k};(a.output/f'manifest-{k}-private.json').write_text(json.dumps([e for e in rows if e['method'] in chosen],ensure_ascii=False,indent=2))
 print('transfer windows',len(rows),'methods',names)
if __name__=='__main__':main()
