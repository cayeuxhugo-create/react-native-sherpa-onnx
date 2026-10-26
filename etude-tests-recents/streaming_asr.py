"""Fixed-window diagnostic for two French streaming models; no phone or publication.
These French-only models are comparators, not universal European-language engines.
"""
import argparse,json,time,hashlib
from pathlib import Path
import numpy as np
import sherpa_onnx as sh

def main():
 p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--model-root',type=Path,required=True);p.add_argument('--model',choices=['fr2023','fr2025'],required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--decoding-method',choices=['greedy_search','modified_beam_search'],default='greedy_search');p.add_argument('--beam',type=int,default=4);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False);m=a.model_root
 if a.model=='fr2025':enc,dec,join=[m/(x+'.onnx') for x in ['encoder','decoder','joiner']]
 else:
  enc=next(m.glob('encoder*.int8.onnx'));dec=next(m.glob('decoder*.onnx'));join=next(m.glob('joiner*.int8.onnx'))
 files=[enc,dec,join,m/'tokens.txt'];start=time.perf_counter()
 rec=sh.OnlineRecognizer.from_transducer(tokens=str(files[-1]),encoder=str(enc),decoder=str(dec),joiner=str(join),num_threads=2,decoding_method=a.decoding_method,max_active_paths=a.beam,enable_endpoint_detection=False)
 meta=dict(model=a.model,decodingMethod=a.decoding_method,maxActivePaths=a.beam,language='French model',loadSeconds=time.perf_counter()-start,sherpaVersion=sh.__version__,files={f.name:dict(bytes=f.stat().st_size,sha256=hashlib.sha256(f.read_bytes()).hexdigest()) for f in files},tailPaddingSeconds=.8);out=[]
 for row in json.loads(a.manifest.read_text()):
  x=np.fromfile(row['path'],dtype='<f4');s=rec.create_stream();start=time.perf_counter()
  for i in range(0,len(x),1600):
   s.accept_waveform(16000,x[i:i+1600])
   while rec.is_ready(s):rec.decode_stream(s)
  s.accept_waveform(16000,np.zeros(12800,dtype=np.float32));s.input_finished()
  while rec.is_ready(s):rec.decode_stream(s)
  out.append(dict(row,text=rec.get_result(s),audioSeconds=len(x)/16000,decodeSeconds=time.perf_counter()-start));(a.output/'results-private.json').write_text(json.dumps(dict(metadata=meta,results=out),ensure_ascii=False,indent=2));print(a.model,row['id'],len(out),flush=True)
if __name__=='__main__':main()
