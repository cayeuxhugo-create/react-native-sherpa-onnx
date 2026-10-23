"""Paired offline recognizer benchmark. Keep --output private: contains transcripts.
No denoising, no changes to source PCM, no recognition service or phone access.
Each recognizer runs in its own process, CPU two threads, greedy decoding.
"""
import argparse,hashlib,json,resource,time,wave
from pathlib import Path
import numpy as np
import sherpa_onnx as sherpa

def read(p):
 if p.suffix=='.f32':return np.fromfile(p,dtype='<f4')
 with wave.open(str(p)) as w:
  assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(16000,1,2)
  return np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ['manifest','model-root','output']:p.add_argument('--'+key,type=Path,required=True)
 p.add_argument('--input-gain',type=float,default=1.0)
 p.add_argument('--model',choices=['qwen','parakeet-v3','whisper-small','whisper-turbo'],required=True);a=p.parse_args()
 if a.output.exists():p.error('Choose a new output directory')
 a.output.mkdir(parents=True);m=a.model_root;assert sherpa.__version__=='1.13.8'
 if a.model=='qwen':files=[m/x for x in ['conv_frontend.onnx','encoder.int8.onnx','decoder.int8.onnx']]+sorted((m/'tokenizer').rglob('*'))
 elif a.model=='parakeet-v3':files=[m/x for x in ['encoder.int8.onnx','decoder.int8.onnx','joiner.int8.onnx','tokens.txt']]
 else:
  prefix='small' if a.model=='whisper-small' else 'turbo';files=[m/(prefix+x) for x in ['-encoder.int8.onnx','-decoder.int8.onnx','-tokens.txt']]
 hashes={str(f.relative_to(m)):dict(bytes=f.stat().st_size,sha256=hashlib.sha256(f.read_bytes()).hexdigest()) for f in files if f.is_file()}
 begin=time.perf_counter()
 if a.model=='qwen':
  rec=sherpa.OfflineRecognizer.from_qwen3_asr(conv_frontend=str(files[0]),encoder=str(files[1]),decoder=str(files[2]),tokenizer=str(m/'tokenizer'),num_threads=2,max_total_len=512,max_new_tokens=256)
 elif a.model=='parakeet-v3':
  rec=sherpa.OfflineRecognizer.from_transducer(encoder=str(files[0]),decoder=str(files[1]),joiner=str(files[2]),tokens=str(files[3]),model_type='nemo_transducer',num_threads=2,decoding_method='greedy_search')
 else:rec=sherpa.OfflineRecognizer.from_whisper(encoder=str(files[0]),decoder=str(files[1]),tokens=str(files[2]),language='fr',task='transcribe',num_threads=2,decoding_method='greedy_search',tail_paddings=-1)
 metadata=dict(model=a.model,inputMaxGain=a.input_gain,sherpaVersion=sherpa.__version__,modelFiles=hashes,loadSeconds=time.perf_counter()-begin,numThreads=2,provider='cpu',language='automatic' if a.model=='parakeet-v3' else 'French',qwenMaxTotalLen=512 if a.model=='qwen' else None,qwenMaxNewTokens=256 if a.model=='qwen' else None)
 # Common two-second zero warmup, never counted as a corpus input.
 st=rec.create_stream()
 if a.model=='qwen':st.set_option('language','French')
 st.accept_waveform(16000,np.zeros(32000,dtype=np.float32));begin=time.perf_counter();rec.decode_stream(st);metadata['warmupSeconds']=time.perf_counter()-begin
 rows=json.loads(a.manifest.read_text());results=[]
 for row in rows:
  audio=read(Path(row['path']));assert len(audio)>0 and np.isfinite(audio).all()
  gain=max(1.,min(a.input_gain,.8/max(float(np.max(abs(audio))),1e-30)));audio=audio*gain
  recognition_start=time.perf_counter()
  st=rec.create_stream()
  if a.model=='qwen':st.set_option('language','French')
  st.accept_waveform(16000,audio);start=time.perf_counter();rec.decode_stream(st);wall=time.perf_counter()-start
  results.append(dict(row,text=st.result.text,audioSeconds=len(audio)/16000,decodeSeconds=wall,recognitionSeconds=time.perf_counter()-recognition_start,inputGain=gain))
  metadata['peakProcessRssKiB']=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
  (a.output/'results-private.json').write_text(json.dumps(dict(metadata=metadata,results=results),ensure_ascii=False,indent=2))
  print(a.model,row['id'],'done',len(results),round(wall,3),flush=True)
if __name__=='__main__':main()
