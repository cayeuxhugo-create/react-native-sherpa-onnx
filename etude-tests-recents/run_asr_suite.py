"""Run additional local ASR comparisons sequentially to bound CPU/memory.
Requires private manifests emitted for this study; creates private transcripts.
"""
import argparse,json,subprocess,sys
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--tools',type=Path,required=True);a=p.parse_args();r=a.root;repo=Path(__file__).resolve().parents[1]
 combined=json.loads((r/'baseline-all-private.json').read_text())+json.loads((r/'supplement-all-private.json').read_text());(r/'fr2023-manifest-private.json').write_text(json.dumps(combined,ensure_ascii=False,indent=2))
 jobs=[('fr2023',repo/'etude-tests-recents/streaming_asr.py',r/'fr2023-manifest-private.json',a.tools/'streaming-fr-2023/sherpa-onnx-streaming-zipformer-fr-2023-04-14-mobile','fr2023')]
 jobs += [(tag,repo/'etude-reconnaisseurs/benchmark.py',r/'supplement-noisy-private.json',a.tools/'asr-comparison'/folder,model) for tag,folder,model in [('parakeet-supplement','sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8','parakeet-v3'),('turbo-supplement','sherpa-onnx-whisper-turbo','whisper-turbo'),('small-supplement','sherpa-onnx-whisper-small','whisper-small')]]
 for tag,script,manifest,modelroot,model in jobs:
  print('START',tag,flush=True)
  with (r/(tag+'.log')).open('w') as log:
   subprocess.run([sys.executable,str(script),'--manifest',str(manifest),'--model-root',str(modelroot),'--output',str(r/tag),'--model',model],stdout=log,stderr=subprocess.STDOUT,check=True)
  print('DONE',tag,flush=True)
if __name__=='__main__':main()
