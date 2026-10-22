"""Offline paired denoising experiment; all transcripts/audio stay in --output.
Inputs are fixed PCM windows, never fresh microphone captures. No VAD gating of
the ASR experiment: reject decisions are separately recorded, including negatives.
UL-UNAS uses the official centered periodic-Hann STFT (16 ms lookahead).
"""
import argparse,hashlib,json,subprocess,time,wave,sys
from pathlib import Path
import numpy as np
import onnxruntime as ort
import sherpa_onnx as sherpa
from scipy.signal import resample_poly
from scipy.io import wavfile

def read(path):
 if path.suffix=='.f32':return np.fromfile(path,dtype='<f4')
 with wave.open(str(path)) as w:
  assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(16000,1,2)
  return np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768

WIN=(.5-.5*np.cos(2*np.pi*np.arange(512)/512)).astype(np.float32)
def stft(x):
 p=np.pad(x,(256,256),mode='reflect');frames=np.lib.stride_tricks.sliding_window_view(p,512)[::256]
 return np.fft.rfft(frames*WIN,axis=1).astype(np.complex64)
def istft(z,n):
 frames=np.fft.irfft(z,n=512,axis=1)*WIN;y=np.zeros((len(z)-1)*256+512);weight=np.zeros_like(y)
 for i,f in enumerate(frames):y[i*256:i*256+512]+=f;weight[i*256:i*256+512]+=WIN**2
 y/=np.maximum(weight,1e-12)
 return y[256:256+n].astype(np.float32)

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for k in ['manifest','output','ulunas','gtcrn','deepfilter','firered','qwen','qwen-cache','vad-harness']:p.add_argument('--'+k,type=Path,required=True)
 p.add_argument('--methods',nargs='+',default=['original','gtcrn','ulunas','deepfilternet3'])
 a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True);a.qwen_cache.mkdir(parents=True,exist_ok=True)
 rng=np.random.default_rng(20261022);probe=rng.normal(0,.03,16093).astype(np.float32)
 assert np.max(abs(istft(stft(probe),len(probe))-probe))<1e-6
 opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1
 ul=ort.InferenceSession(str(a.ulunas),opts,providers=['CPUExecutionProvider'])
 sys.path.insert(0,str(a.vad_harness));from compare_full_recordings import Models
 vad=Models(a.firered);m=a.qwen
 assert sherpa.__version__=='1.13.8'
 settings=dict(models={str(x.relative_to(m)):hashlib.sha256(x.read_bytes()).hexdigest() for x in sorted(m.rglob('*')) if x.is_file() and (x.suffix=='.onnx' or 'tokenizer' in x.parts)},sherpaVersion=sherpa.__version__,language='French',maxTotalLen=512,maxNewTokens=256,numThreads=2)
 meta=a.qwen_cache/'model-settings.json'
 if meta.exists():assert json.loads(meta.read_text())==settings,'Qwen cache model/settings mismatch'
 else:meta.write_text(json.dumps(settings,indent=2))
 q=sherpa.OfflineRecognizer.from_qwen3_asr(conv_frontend=str(m/'conv_frontend.onnx'),encoder=str(m/'encoder.int8.onnx'),decoder=str(m/'decoder.int8.onnx'),tokenizer=str(m/'tokenizer'),num_threads=2,max_total_len=512,max_new_tokens=256)
 results=[];rows=json.loads(a.manifest.read_text())
 for row in rows:
  x=read(Path(row['path']));folder=a.output/row['id'];folder.mkdir(exist_ok=True)
  for method in a.methods:
   destination=folder/(method+'.f32');start=time.perf_counter();gain=1.
   if destination.exists():y=np.fromfile(destination,dtype='<f4');enhance_seconds=None
   else:
    base=method
    if '-gain' in method:
     base,limit=method.rsplit('-gain',1);gain=max(1.,min(float(limit),.8/max(float(np.max(abs(x))),1e-30)))
    inp=x*gain
    if base=='original':y=inp.copy()
    elif base=='gtcrn':
     c=sherpa.OnlineSpeechDenoiserConfig();c.model.gtcrn.model=str(a.gtcrn);c.model.num_threads=1;d=sherpa.OnlineSpeechDenoiser(c)
     y=np.concatenate([np.asarray(d.run(inp,16000).samples,dtype=np.float32),np.asarray(d.flush().samples,dtype=np.float32)])[:len(x)]
    elif base=='ulunas':
     z=stft(inp);cache={i.name:np.zeros(i.shape,dtype=np.float32) for i in ul.get_inputs() if i.name!='mix'};out=[]
     for frame in z:
      v=np.stack([frame.real,frame.imag],axis=-1)[None,:,None,:].astype(np.float32)
      enhanced,*states=ul.run(None,dict(mix=v,**cache));cache=dict(zip(cache,states));out.append(enhanced[0,:,0,0]+1j*enhanced[0,:,0,1])
     y=istft(np.asarray(out),len(x))
    elif base=='deepfilternet3':
     source=folder/(method+'-48k.wav');wavfile.write(source,48000,np.pad(resample_poly(inp,3,1),(0,4800)).astype(np.float32))
     out=folder/(method+'-out');out.mkdir(exist_ok=True)
     run=subprocess.run([str(a.deepfilter),'-D','-o',str(out),str(source)],capture_output=True,text=True,check=True)
     (folder/(method+'.log')).write_text(run.stdout+run.stderr)
     target=next(out.glob('*.wav'));sr,y48=wavfile.read(target);assert sr==48000
     if y48.dtype.kind=='i':y48=y48.astype(np.float32)/(2**(8*y48.dtype.itemsize-1))
     y=resample_poly(y48,1,3)[:len(x)].astype(np.float32)
    elif base in ['resample-control','pcm16-resample-control']:
     mid=resample_poly(inp,3,1)
     if base=='pcm16-resample-control':mid=(np.clip(mid,-1,1)*32767).astype(np.int16).astype(np.float32)/32768
     y=resample_poly(mid,1,3)[:len(x)].astype(np.float32)
    else:raise ValueError(method)
    assert len(y)==len(x),(method,len(y),len(x))
    y=(y/gain).astype('<f4');assert np.isfinite(y).all();y.tofile(destination);enhance_seconds=time.perf_counter()-start
   data=y.astype('<f4').tobytes();key=hashlib.sha256(data+b'Guardian-Qwen1.13.8-French-max512-new256-threads2').hexdigest();cache=a.qwen_cache/(key+'.json')
   if cache.exists():decoded=json.loads(cache.read_text());hit=True
   else:
    st=q.create_stream();st.set_option('language','French');st.accept_waveform(16000,y);begin=time.perf_counter();q.decode_stream(st);decoded=dict(text=st.result.text,decodeWallSeconds=time.perf_counter()-begin);cache.write_text(json.dumps(decoded,ensure_ascii=False));hit=False
   accepted=any(vad.decision(y))
   record=dict(row,method=method,audioSeconds=len(x)/16000,enhanceWallSeconds=enhance_seconds,peak=float(np.max(abs(y))),rms=float(np.sqrt(np.mean(y**2))),vadAccepted=accepted,cacheHit=hit,**decoded)
   results.append(record);(a.output/'results-private.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print(row['id'],method,'vad',accepted,'done',len(results),flush=True)

if __name__=='__main__':main()
