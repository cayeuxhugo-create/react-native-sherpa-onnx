"""Sensitivity checks on the same clips; not additional independent recordings.
Uses WebRTC mode1, bounded internal gain4, >=180ms total positive decisions and
>=60ms consecutive. Perturbations affect only the confirmation copy.
"""
import argparse,json,time
from pathlib import Path
import numpy as np,webrtcvad
from evaluate import read,level,features

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if a.output.exists():p.error('Choose a new output')
 rows=json.loads(a.manifest.read_text());results=[]
 for db in [-6,0,6]:
  for offset in [0,160,320]:
   records=[];start=time.monotonic()
   for row in rows:
    audio=read(Path(row['path']))[offset:]*10**(db/20);v=webrtcvad.Vad(1);flags=[]
    for i in range(0,len(audio),480):
     x=audio[i:i+480]
     if len(x)<480:x=np.pad(x,(0,480-len(x)))
     b=np.round(np.clip(level(x,4),-1,32767/32768)*32768).astype('<i2').tobytes();flags.append(v.is_speech(b,16000))
    positive,longest=features(flags,.03);records.append(dict(id=row['id'],label=row['label'],accepted=positive+1e-8>=.18 and longest+1e-8>=.06))
   counts={label:{'accepted':sum(x['accepted'] for x in records if x['label']==label),'total':sum(x['label']==label for x in records)} for label in ['negative','positive','short-word']}
   result=dict(db=db,offsetMs=offset/16,counts=counts,decisions=records,wallSeconds=time.monotonic()-start);results.append(result);print(db,offset/16,counts,flush=True)
 a.output.write_text(json.dumps(results,indent=2))
if __name__=='__main__':main()
