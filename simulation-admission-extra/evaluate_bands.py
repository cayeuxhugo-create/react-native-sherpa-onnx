"""Speech-band filtering of the confirmation copy only, before WebRTC VAD.
Filters start fresh on each completed segment; Qwen input is untouched.
"""
import argparse,importlib.util,json,time
from pathlib import Path
import numpy as np,webrtcvad
from scipy.signal import butter,sosfilt
spec=importlib.util.spec_from_file_location('base_eval',Path(__file__).resolve().parents[1]/'simulation-admission/evaluate.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if a.output.exists():p.error('Choose new output')
 data=[(row,base.read(Path(row['path']))) for row in json.loads(a.manifest.read_text())];results=[]
 for low,high in [(80,4000),(150,4000),(300,3400)]:
  filtered=[(row,sosfilt(butter(4,[low,high],btype='bandpass',fs=16000,output='sos'),audio).astype(np.float32)) for row,audio in data]
  for mode in [1,2]:
   for gain in [4,16]:
    start=time.monotonic();records=[]
    for row,audio in filtered:
     v=webrtcvad.Vad(mode);flags=[]
     for i in range(0,len(audio),480):
      x=audio[i:i+480]
      if len(x)<480:x=np.pad(x,(0,480-len(x)))
      pcm=np.round(np.clip(base.level(x,gain),-1,32767/32768)*32768).astype('<i2').tobytes();flags.append(v.is_speech(pcm,16000))
     positive,longest=base.features(flags,.03);records.append(dict(row,positiveSeconds=positive,longestSeconds=longest))
    for required in [.18,.3]:
     result=base.summarize(f'band{low}-{high}-mode{mode}-gain{gain}-support{required}',records,lambda r:r['positiveSeconds']+1e-8>=required and r['longestSeconds']+1e-8>=.06,time.monotonic()-start);results.append(result);print(result['candidate'],result['counts'],flush=True)
 a.output.write_text(json.dumps({'results':results},indent=2))
if __name__=='__main__':main()
