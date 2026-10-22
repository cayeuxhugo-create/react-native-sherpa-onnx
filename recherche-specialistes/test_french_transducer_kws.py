"""Research-only KWS using French streaming transducers, no VAD gating.
BPE model files are unavailable: enumerate all token-table decompositions for the
five short test expressions. This is not an official pretrained French KWS model.
Outputs are private per-input detections; publish aggregated counts only.
"""
import argparse,functools,hashlib,json,time,wave
from pathlib import Path
import numpy as np,sherpa_onnx as sherpa

def read(p):
 if p.suffix=='.f32':return np.fromfile(p,dtype='<f4')
 with wave.open(str(p)) as w:
  assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,2,16000)
  return np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768

def keywords(tokens,uppercase):
 vocab={x.rsplit(' ',1)[0] for x in tokens.read_text().splitlines()};vocab={x for x in vocab if not x.startswith('<')}
 @functools.lru_cache(None)
 def encode(text):
  if not text:return [()]
  result=[]
  for piece in sorted(vocab):
   if text.startswith(piece):result.extend((piece,)+tail for tail in encode(text[len(piece):]))
  if len(result)>10000:raise ValueError('Too many decompositions')
  return sorted(set(result),key=lambda p:(len(p),p))
 output=[];counts={}
 for word in ['aide','au secours','non','oui','stop']:
  text='▁'+'▁'.join((word.upper() if uppercase else word.lower()).split());paths=encode(text);assert paths
  counts[word]=len(paths)
  for path in paths:output.append(' '.join(path)+' @'+word.replace(' ','_'))
 return '\n'.join(output)+'\n',counts

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for k in ['model','manifest','output']:p.add_argument('--'+k,type=Path,required=True)
 p.add_argument('--variant',choices=['kroko2025','mobile2023-int8','mobile2023-fp32'],required=True);p.add_argument('--profile',choices=['default','sensitive','strict'],default='default');p.add_argument('--gain',type=float,default=1);a=p.parse_args()
 if a.output.exists():p.error('Choose new output directory')
 a.output.mkdir(parents=True);m=a.model
 kw,counts=keywords(m/'tokens.txt',a.variant!='kroko2025');kwpath=a.output/'keywords.txt';kwpath.write_text(kw)
 if a.variant=='kroko2025':enc=m/'encoder.onnx';dec=m/'decoder.onnx';join=m/'joiner.onnx'
 else:enc=m/('encoder-epoch-29-avg-9-with-averaged-model'+('.int8' if a.variant.endswith('int8') else '')+'.onnx');dec=m/'decoder-epoch-29-avg-9-with-averaged-model.onnx';join=m/'joiner-epoch-29-avg-9-with-averaged-model.int8.onnx'
 score,threshold={'default':(1.,.25),'sensitive':(2.,.15),'strict':(1.,.5)}[a.profile]
 spot=sherpa.KeywordSpotter(tokens=str(m/'tokens.txt'),encoder=str(enc),decoder=str(dec),joiner=str(join),keywords_file=str(kwpath),num_threads=1,max_active_paths=4,keywords_score=score,keywords_threshold=threshold,num_trailing_blanks=1)
 model_hashes={x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in [enc,dec,join,m/'tokens.txt']}
 rows=json.loads(a.manifest.read_text());results=[]
 for row in rows:
  audio=read(Path(row['path']));st=spot.create_stream();events=[];start=time.perf_counter()
  for i in range(0,len(audio),800):
   x=audio[i:i+800];g=max(1.,min(a.gain,.8/max(float(np.max(abs(x))),1e-30)));st.accept_waveform(16000,x*g)
   while spot.is_ready(st):
    spot.decode_stream(st);text=spot.get_result(st)
    if text:events.append(dict(keyword=text,availableSeconds=min(i+800,len(audio))/16000));spot.reset_stream(st)
  # No artificial EOF silence or forced input_finished flush.
  result=dict(id=row['id'],label=row['label'],expected=row.get('expected'),audioSeconds=len(audio)/16000,wallSeconds=time.perf_counter()-start,events=events);results.append(result)
  (a.output/'results-private.json').write_text(json.dumps(dict(variant=a.variant,profile=a.profile,score=score,threshold=threshold,maxInputGain=a.gain,bpeDecompositions=counts,modelHashes=model_hashes,results=results),ensure_ascii=False,indent=2));print(row['id'],'events',len(events),flush=True)
if __name__=='__main__':main()
