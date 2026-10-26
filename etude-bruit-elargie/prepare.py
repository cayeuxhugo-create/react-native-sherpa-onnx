"""Private-corpus denoising study. Outputs contain audio/transcripts: keep private.
No online recognition. Continuous and completed-window methods are distinguished.
"""
import argparse,ctypes as C,json,time,wave,subprocess,hashlib
from pathlib import Path
import numpy as np
from scipy.signal import resample_poly,butter,sosfilt,find_peaks,iirnotch,lfilter
from scipy.ndimage import uniform_filter1d
from scipy.io import wavfile
WIN=(.5-.5*np.cos(2*np.pi*np.arange(512)/512)).astype(np.float32)
def stft(x):
 p=np.pad(x,(256,512),mode='reflect');f=np.lib.stride_tricks.sliding_window_view(p,512)[::256]
 return np.fft.rfft(f*WIN,axis=1)
def istft(z,n):
 f=np.fft.irfft(z,n=512,axis=1)*WIN;y=np.zeros((len(z)-1)*256+512);w=np.zeros_like(y)
 for i,v in enumerate(f):y[i*256:i*256+512]+=v;w[i*256:i*256+512]+=WIN**2
 return (y[256:256+n]/np.maximum(w[256:256+n],1e-12)).astype(np.float32)
def hp(x):return lfilter([.97,-.97],[1,-.97],x).astype(np.float32)
def noise_power(z,seconds=3):
 # Explicit protocol assumption: the initial 0.5..3 s are without speech.
 return np.maximum(np.mean(abs(z[32:round(seconds*16000/256)])**2,axis=0),1e-18)
def spectral(x,kind,over=1.,floor=.25,seconds=3,alpha=.98):
 z=stft(x);p=abs(z)**2;n=noise_power(z,seconds);gamma=p/n
 if kind=='wiener':g=np.sqrt(np.maximum(0,1-over/np.maximum(gamma,1e-8)))
 elif kind=='subtract':g=np.maximum(0,1-over/np.sqrt(np.maximum(gamma,1e-8)))
 elif kind=='dd':
  out=[];prior=np.zeros(z.shape[1]);previous=np.ones_like(prior)
  for row in gamma:
   xi=alpha*previous**2*prior+(1-alpha)*np.maximum(row-1,0);g0=xi/(1+xi);out.append(g0);prior=row;previous=np.maximum(floor,g0)
  g=np.asarray(out)
 else:raise ValueError(kind)
 g=uniform_filter1d(np.maximum(g,floor),size=3,axis=1,mode='nearest')
 # Causal temporal smoothing, not future speech-based mask selection.
 g=lfilter([.35],[1,-.65],g,axis=0)
 return istft(z*g,len(x))
def notch(x):
 p=noise_power(stft(x));freq=np.arange(len(p))*16000/512;peaks,_=find_peaks(10*np.log10(p),prominence=5,distance=3);peaks=[i for i in peaks if 90<freq[i]<3500];peaks=sorted(peaks,key=lambda i:p[i],reverse=True)[:3];y=x.copy()
 for i in peaks:b,a=iirnotch(freq[i],20,fs=16000);y=lfilter(b,a,y)
 return y.astype(np.float32)
def rnnoise(x,libpath):
 lib=C.CDLL(str(libpath));lib.rnnoise_create.restype=C.c_void_p;lib.rnnoise_process_frame.argtypes=[C.c_void_p,C.POINTER(C.c_float),C.POINTER(C.c_float)];lib.rnnoise_process_frame.restype=C.c_float;lib.rnnoise_destroy.argtypes=[C.c_void_p]
 a=resample_poly(x,3,1).astype(np.float32)*32768;pad=(-len(a))%480;a=np.pad(a,(0,pad+480));out=[];state=lib.rnnoise_create();assert state
 try:
  for i in range(0,len(a),480):
   frame=np.ascontiguousarray(a[i:i+480]);y=np.empty(480,dtype=np.float32);lib.rnnoise_process_frame(state,y.ctypes.data_as(C.POINTER(C.c_float)),frame.ctypes.data_as(C.POINTER(C.c_float)));out.append(y/32768)
 finally:lib.rnnoise_destroy(state)
 return resample_poly(np.concatenate(out)[480:480+len(x)*3],1,3)[:len(x)].astype(np.float32)
def speex(x,path,db,maximum=1):
 lib=C.CDLL(str(path));lib.speex_preprocess_state_init.argtypes=[C.c_int,C.c_int];lib.speex_preprocess_state_init.restype=C.c_void_p;lib.speex_preprocess_ctl.argtypes=[C.c_void_p,C.c_int,C.c_void_p];lib.speex_preprocess_run.argtypes=[C.c_void_p,C.POINTER(C.c_int16)];lib.speex_preprocess_state_destroy.argtypes=[C.c_void_p]
 gain=max(1.,min(maximum,.8/max(float(np.max(abs(x))),1e-20)));a=np.pad(x, (0,(-len(x))%320+320));out=[];s=lib.speex_preprocess_state_init(320,16000);assert s
 try:
  for code,v in [(0,1),(2,0),(4,0),(18,db)]:
   value=C.c_int(v);assert lib.speex_preprocess_ctl(s,code,C.byref(value))==0
  for i in range(0,len(a),320):
   frame=np.ascontiguousarray(np.clip(np.rint(a[i:i+320]*gain*32768),-32768,32767).astype(np.int16));lib.speex_preprocess_run(s,frame.ctypes.data_as(C.POINTER(C.c_int16)));out.append(frame.astype(np.float32)/(32768*gain))
 finally:lib.speex_preprocess_state_destroy(s)
 # Speex overlap-add output is delayed by one 20ms frame.
 return np.concatenate(out)[320:320+len(x)]
def gtcrn(x,model,maximum=1):
 import sherpa_onnx as sh
 gain=max(1.,min(maximum,.8/max(float(np.max(abs(x))),1e-20)));c=sh.OnlineSpeechDenoiserConfig();c.model.gtcrn.model=str(model);c.model.num_threads=1;d=sh.OnlineSpeechDenoiser(c);a=x*gain
 y=np.concatenate([np.asarray(d.run(a,16000).samples,dtype=np.float32),np.asarray(d.flush().samples,dtype=np.float32)])[:len(x)]
 assert len(y)==len(x);return y/gain

def deepfilter(x,tools,folder,maximum=1,atten=100,threshold=-15,model='3'):
 gain=max(1.,min(maximum,.8/max(float(np.max(abs(x))),1e-20)));a=np.pad(resample_poly(x*gain,3,1),(0,4800)).astype(np.float32);f=folder/'df-input.wav';wavfile.write(f,48000,a);o=folder/'df-output';o.mkdir(exist_ok=True)
 cmd=[str(tools/'deepfilter/deep-filter')]+(['-D'] if atten>0 else [])+['-a',str(atten),'--min-db-thresh='+str(threshold),'-o',str(o)]
 models=tools/'deepfilter/source/models'
 if model=='2':cmd+=['-m',str(models/'DeepFilterNet2_onnx.tar.gz')]
 if model=='3ll':cmd+=['-m',str(models/'DeepFilterNet3_ll_onnx.tar.gz')]
 run=subprocess.run(cmd+[str(f)],capture_output=True,text=True,timeout=90);(folder/'df.log').write_text(run.stdout+run.stderr);assert run.returncode==0,run.stderr
 sr,y=wavfile.read(o/f.name);assert sr==48000
 if y.dtype.kind=='i':y=y.astype(np.float32)/32768
 y=resample_poly(y,1,3)[:len(x)].astype(np.float32)/gain;assert len(y)==len(x)
 return y

def multichannel(stereo,kind,mu=1.,ref=0,alpha=.97,load=.02):
 z=np.stack([stft(stereo[:,i]) for i in range(2)],axis=-1);outer=z[:,:,:,None]*z[:,:,None,:].conj();n=np.mean(outer[32:188],axis=0);eye=np.eye(2);n=n+load*np.trace(n,axis1=-2,axis2=-1).real[...,None,None]/2*eye+1e-12*eye
 rx=lfilter([1-alpha],[1,-alpha],outer,axis=0);rs=rx-n
 vals,vectors=np.linalg.eigh(rs);vals=np.maximum(vals,0);rs=np.einsum('...ik,...k,...jk->...ij',vectors,vals,vectors.conj())
 if kind=='mwf':
  rhs=rs[...,ref];w=np.linalg.solve(rs+mu*n+1e-10*eye,rhs[...,None])[...,0]
 else:
  d=vectors[...,:,-1];den=d[...,ref];valid=abs(den)>.1;d=d/np.where(valid,den,1)[...,None];w=np.linalg.solve(np.broadcast_to(n,rs.shape),d[...,None])[...,0];den=np.sum(d.conj()*w,axis=-1).real;w=w/np.maximum(den,1e-9)[...,None]
  safe=np.zeros_like(w);safe[...,ref]=1;w=np.where(valid[...,None],w,safe)
 norm=np.sqrt(np.sum(abs(w)**2,axis=-1));w=w/np.maximum(1,norm/3)[...,None]
 out=np.sum(w.conj()*z,axis=-1);return hp(istft(out,len(stereo)))

def configs():
 c=[dict(name='original',family='reference')]
 c += [dict(name=f'hp{f}',family='highpass',hz=f) for f in [150,300,450]]
 c += [dict(name=f'wiener-o{o}-f{f}',family='wiener',over=o,floor=f) for o in [.5,1,2] for f in [.25,.5,.75]]
 c += [dict(name=f'dd-a{a}-f{f}',family='dd',alpha=a,floor=f) for a in [.92,.98] for f in [.15,.4]]
 c += [dict(name=f'sub-o{o}',family='subtract',over=o,floor=.3) for o in [.5,1]]
 c += [dict(name='notch3',family='notch')]
 c += [dict(name=f'speex{db}',family='speex',db=db) for db in [-6,-12,-24,-36]]
 c += [dict(name='rnnoise-v01',family='rnnoise'),dict(name='rnnoise-mix50',family='rnnoise',blend=.5)]
 c += [dict(name='gtcrn',family='gtcrn'),dict(name='gtcrn-mix50',family='gtcrn',blend=.5)]
 # Constant model input normalization is available only once a window is completed.
 c += [dict(name=f'seg-df3-g{g}-a{a}',family='df',segmented=True,gain=g,atten=a) for g,a in [(1,100),(16,12),(16,24),(16,100)]]
 c += [dict(name='seg-df3-lowthresh',family='df',segmented=True,gain=16,atten=100,threshold=-40)]
 c += [dict(name=f'seg-df{m}',family='df',segmented=True,gain=16,atten=24,model=m) for m in ['2','3ll']]
 c += [dict(name='seg-speex24-g8',family='speex',segmented=True,db=-24,gain=8)]
 return c

def apply(x,c,tools,folder,stereo=None):
 fam=c['family']
 if fam=='reference':return x.copy()
 if fam=='highpass':return sosfilt(butter(3,c['hz'],fs=16000,btype='highpass',output='sos'),x).astype(np.float32)
 if fam in ['wiener','subtract','dd']:return spectral(x,fam,c.get('over',1),c.get('floor',.25),c.get('seconds',3),c.get('alpha',.98))
 if fam=='notch':return notch(x)
 if fam=='rnnoise':y=rnnoise(x,tools/'rnnoise-v01/librnnoise.so')
 elif fam=='gtcrn':y=gtcrn(x,Path(__file__).resolve().parents[1]/'modules/guardian-native-protection/android/src/main/assets/gtcrn_simple.onnx',c.get('gain',1))
 elif fam=='speex':y=speex(x,tools/'speexdsp-1.2.1/libspeexdsp.so',c['db'],c.get('gain',1))
 elif fam=='df':y=deepfilter(x,tools,folder,c.get('gain',1),c.get('atten',100),c.get('threshold',-15),c.get('model','3'))
 elif fam in ['mwf','mvdr']:
  y=multichannel(stereo,fam,c.get('mu',1),c.get('ref',0),c.get('alpha',.97),c.get('load',.02))
  blend=c.get('blend',1);return (blend*y+(1-blend)*hp(stereo[:,c.get('ref',0)])).astype(np.float32)
 elif fam=='channel':return hp(stereo[:,c['ref']])
 elif fam=='mean':return hp(stereo.mean(axis=1))
 else:raise ValueError(fam)
 return (c.get('blend',1)*y+(1-c.get('blend',1))*x).astype(np.float32)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--dataset',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--tools',type=Path,required=True);p.add_argument('--configs',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 rng=np.random.default_rng(41);probe=rng.normal(0,.1,16193).astype(np.float32);assert np.max(abs(istft(stft(probe),len(probe))-probe))<1e-6
 cs=json.loads(a.configs.read_text()) if a.configs else configs();records=[];metrics=[];failures=[]
 for dataset in json.loads(a.dataset.read_text()):
  x=np.fromfile(dataset['path'],dtype='<f4');stereo=None
  if dataset.get('stereo'):
   with wave.open(dataset['stereo']) as w:assert w.getframerate()==16000 and w.getnchannels()==2;stereo=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').reshape(-1,2).astype(np.float32)/32768
  for c in cs:
   folder=a.output/dataset['id']/c['name'];folder.mkdir(parents=True,exist_ok=True);start=time.perf_counter()
   try:
    full=None;cached=False;processed=0
    signature=json.dumps(dict(config=c,input=str(Path(dataset['path']).resolve()),bytes=Path(dataset['path']).stat().st_size,mtime=Path(dataset['path']).stat().st_mtime_ns,stereo=dataset.get('stereo')),sort_keys=True)
    stamp=folder/'parameters.json'
    if stamp.exists() and stamp.read_text()!=signature:raise ValueError('Output parameters changed; use a fresh output directory')
    if not stamp.exists() and any(folder.glob('*.f32')):raise ValueError('Unversioned cache; use a fresh output directory')
    stamp.write_text(signature)
    if not c.get('segmented'):
     file=folder/'full.f32'
     if file.exists():full=np.fromfile(file,dtype='<f4');cached=True
     else:
      processed+=len(x)/16000;full=apply(x,c,a.tools,folder,stereo);assert len(full)==len(x) and np.isfinite(full).all();full.astype('<f4').tofile(file)
    for n,(pos,text) in enumerate(zip(dataset['prompts'],dataset['texts']),1):
     for shift in dataset.get('shifts',[-.5,1.5]):
      begin=max(0,round((pos+shift)*16000));end=min(len(x),begin+128000);piece=folder/f'prompt-{n}-shift{shift}.f32'
      if piece.exists():y=np.fromfile(piece,dtype='<f4');cached=True
      elif full is not None:y=full[begin:end];y.astype('<f4').tofile(piece)
      else:
       tmp=folder/f'piece-{n}-{shift}';tmp.mkdir(exist_ok=True);processed+=(end-begin)/16000;y=apply(x[begin:end],c,a.tools,tmp);assert len(y)==end-begin and np.isfinite(y).all();y.astype('<f4').tofile(piece)
      records.append(dict(id=f"{dataset['id']}-{c['name']}-p{n}-s{shift}",path=str(piece),dataset=dataset['id'],method=c['name'],family=c['family'],segmented=c.get('segmented',False),prompt=n,shift=shift,expected=text))
    metrics.append(dict(dataset=dataset['id'],method=c['name'],seconds=time.perf_counter()-start,audioSeconds=len(x)/16000,processedAudioSeconds=processed,cacheUsed=cached,fullFile=str(folder/'full.f32') if full is not None else None))
    print(dataset['id'],c['name'],'ready',len(records),flush=True)
   except Exception as e:
    failures.append(dict(dataset=dataset['id'],method=c['name'],error=str(e)));print('FAILED',dataset['id'],c['name'],str(e),flush=True)
   (a.output/'manifest-private.json').write_text(json.dumps(records,ensure_ascii=False,indent=2));(a.output/'metrics.json').write_text(json.dumps(metrics,indent=2));(a.output/'failures.json').write_text(json.dumps(failures,indent=2))
 (a.output/'configs.json').write_text(json.dumps(cs,indent=2))
if __name__=='__main__':main()
