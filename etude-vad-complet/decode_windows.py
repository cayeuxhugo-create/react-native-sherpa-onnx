"""Local Qwen decoding of admitted windows, cached by PCM bytes and model settings.
Private outputs include transcripts. A cache hit is reported, not timed as inference.
"""
import argparse,hashlib,json,time
from pathlib import Path
import numpy as np,sherpa_onnx as sherpa

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ['results','qwen','cache','output']:p.add_argument('--'+key,type=Path,required=True)
 a=p.parse_args();a.cache.mkdir(parents=True,exist_ok=True);m=a.qwen
 assert sherpa.__version__=='1.13.8'
 fingerprints={str(x.relative_to(m)):hashlib.sha256(x.read_bytes()).hexdigest() for x in sorted(m.rglob('*')) if x.is_file() and (x.suffix=='.onnx' or 'tokenizer' in x.parts)}
 metadata=dict(models=fingerprints,sherpaVersion=sherpa.__version__,language='French',maxTotalLen=512,maxNewTokens=256,numThreads=2)
 meta=a.cache/'model-settings.json'
 if meta.exists():assert json.loads(meta.read_text())==metadata,'Cache model/settings mismatch'
 else:meta.write_text(json.dumps(metadata,indent=2))
 q=sherpa.OfflineRecognizer.from_qwen3_asr(conv_frontend=str(m/'conv_frontend.onnx'),encoder=str(m/'encoder.int8.onnx'),decoder=str(m/'decoder.int8.onnx'),tokenizer=str(m/'tokenizer'),num_threads=2,max_total_len=512,max_new_tokens=256)
 rows=json.loads(a.results.read_text());done=[];new=cached=0
 for record in rows:
  outputs=[]
  for window in record['windows']:
   if not window['accepted']:continue
   p=Path(window['path']);data=p.read_bytes();key=hashlib.sha256(data+b'Guardian-Qwen1.13.8-French-max512-new256-threads2').hexdigest();cache=a.cache/(key+'.json')
   if cache.exists():r=json.loads(cache.read_text());hit=True;cached+=1
   else:
    pcm=np.frombuffer(data,dtype='<f4');st=q.create_stream();st.set_option('language','French');st.accept_waveform(16000,pcm);start=time.perf_counter();q.decode_stream(st);r=dict(text=st.result.text,decodeWallSeconds=time.perf_counter()-start);cache.write_text(json.dumps(r,ensure_ascii=False));hit=False;new+=1
   outputs.append(dict(window,**r,cacheHit=hit))
  done.append(dict(record,decoded=outputs));a.output.write_text(json.dumps(done,ensure_ascii=False,indent=2));print(record['inputId'],record['db'],record['offsetMs'],record['strategy'],'decoded',len(outputs),'new',new,'cached',cached,flush=True)
if __name__=='__main__':main()
