"""Window-level Silero/TEN controls, not an Android segmentation comparison."""
import argparse,json,hashlib
from pathlib import Path
import numpy as np
import sherpa_onnx as sh

def main():
 p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--tools',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();out=[]
 silero=Path(__file__).resolve().parents[1]/'modules/guardian-native-protection/android/src/main/assets/silero_vad.onnx'
 for row in json.loads(a.manifest.read_text()):
  raw=np.fromfile(row['path'],dtype='<f4')
  for model in ['silero','ten']:
   path=silero if model=='silero' else a.tools/'ten-vad.onnx'
   for maxgain in [1,16,64]:
    c=sh.VadModelConfig();c.sample_rate=16000;c.num_threads=1
    cfg=c.silero_vad if model=='silero' else c.ten_vad;cfg.model=str(path);cfg.threshold=.5;cfg.min_speech_duration=.2;cfg.min_silence_duration=.5
    vad=sh.VoiceActivityDetector(c,buffer_size_in_seconds=30)
    gain=max(1,min(maxgain,.8/max(float(np.max(abs(raw))),1e-20)));x=raw*gain;flags=[]
    for pos in range(0,len(x),800):
     vad.accept_waveform(x[pos:pos+800]);flags.append(vad.is_speech_detected())
     while not vad.empty():vad.pop()
    out.append(dict(row,model=model,maxGain=maxgain,appliedGain=gain,accepted=any(flags),modelSha256=hashlib.sha256(path.read_bytes()).hexdigest()))
 a.output.write_text(json.dumps(out,ensure_ascii=False,indent=2));print('VAD comparisons',len(out))
if __name__=='__main__':main()
