"""Exploratory segment confirmation on original PCM. No Android changes.
Uses manifest schema from simulation-admission/evaluate.py. TEN model is not
redistributed; its additional license terms need review before product use.
"""
import argparse,importlib.util,json,time
from pathlib import Path
import numpy as np,sherpa_onnx as s
spec=importlib.util.spec_from_file_location('base_eval',Path(__file__).resolve().parents[1]/'simulation-admission/evaluate.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--model',type=Path,required=True);p.add_argument('--family',choices=['silero','ten'],required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--sensitive',action='store_true',help='Silero only: probability .1/.15, duration96/128ms, gainMax64/256')
 a=p.parse_args()
 if a.sensitive and a.family!='silero':p.error('Sensitive grid is Silero only')
 if a.output.exists():p.error('Choose new output')
 data=[(row,base.read(Path(row['path']))) for row in json.loads(a.manifest.read_text())];results=[]
 for threshold in ([.1,.15] if a.sensitive else [.25,.5,.75]):
  for duration in ([.096,.128] if a.sensitive else [.128,.192]):
   for gain in ([64,256] if a.sensitive else ([16,64] if a.family=='silero' else [4,16,64])):
    config=s.VadModelConfig();model=config.silero_vad if a.family=='silero' else config.ten_vad;model.model=str(a.model);model.threshold=threshold;model.min_speech_duration=duration;model.min_silence_duration=.128;model.max_speech_duration=30;model.window_size=512 if a.family=='silero' else 256;config.sample_rate=16000;config.num_threads=1
    v=s.VoiceActivityDetector(config,buffer_size_in_seconds=40);records=[];start=time.monotonic()
    for row,audio in data:
     v.reset();flags=[]
     for i in range(0,len(audio),800):
      v.accept_waveform(base.level(audio[i:i+800],gain));flags.append(v.is_speech_detected())
      while not v.empty():v.pop()
     positive,longest=base.features(flags,.05);records.append(dict(row,positiveSeconds=positive,longestSeconds=longest))
    result=base.summarize(f'{a.family}-threshold{threshold}-duration{duration}-gain{gain}',records,lambda r:r['positiveSeconds']+1e-8>=.1,time.monotonic()-start);results.append(result);print(result['candidate'],result['counts'],flush=True)
    a.output.write_text(json.dumps({'family':a.family,'results':results},indent=2))
if __name__=='__main__':main()
