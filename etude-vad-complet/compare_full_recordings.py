"""Causal full-recording comparison: current v3.3, FireRed streaming, sliding FireRed.
Private outputs contain transcripts/audio paths. Publish only aggregates.
No Android changes, no remote inference, no synthetic EOF flush.
"""
import argparse,csv,hashlib,importlib.util,json,math,struct,subprocess,sys,time,types,wave
from pathlib import Path
import numpy as np,onnxruntime as ort,kaldi_native_fbank as knf,kaldiio
SR=16000;FRAME=800;HOP=8000

def read(p):
 if p.suffix=='.f32':return np.fromfile(p,dtype='<f4')
 with wave.open(str(p)) as w:
  assert (w.getnchannels(),w.getsampwidth(),w.getframerate())==(1,2,SR)
  return np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768

def gain(x,maxgain=64):return x*max(1.,min(maxgain,.8/max(float(np.max(abs(x))),1e-30)))

def frontend(x,stats):
 opts=knf.FbankOptions();opts.frame_opts.samp_freq=SR;opts.frame_opts.dither=0;opts.frame_opts.snip_edges=True;opts.frame_opts.frame_length_ms=25;opts.frame_opts.frame_shift_ms=10;opts.mel_opts.num_bins=80;opts.mel_opts.debug_mel=False
 f=knf.OnlineFbank(opts);pcm=np.round(np.clip(x,-1,32767/32768)*32768).astype('<i2').astype(np.float32);f.accept_waveform(SR,pcm.tolist())
 if not f.num_frames_ready:return np.zeros((0,80),dtype=np.float32)
 feat=np.vstack([f.get_frame(i) for i in range(f.num_frames_ready)]);mean=stats[0,:80]/stats[0,80];std=np.sqrt(np.maximum(stats[1,:80]/stats[0,80]-mean**2,1e-20));return ((feat-mean)/std).astype(np.float32)

class Models:
 def __init__(self,root):
  o=ort.SessionOptions();o.intra_op_num_threads=1;o.inter_op_num_threads=1
  self.non=ort.InferenceSession(str(root/'pretrained_models/onnx_models/fireredvad_vad.onnx'),o,providers=['CPUExecutionProvider']);self.stream=ort.InferenceSession(str(root/'pretrained_models/onnx_models/fireredvad_stream_vad_with_cache.onnx'),o,providers=['CPUExecutionProvider']);self.stats=kaldiio.load_mat(str(root/'pretrained_models/onnx_models/cmvn.ark'));self.calls=0;self.seconds=0.
  for name,path in [('fireredvad',root/'fireredvad'),('fireredvad.core',root/'fireredvad/core')]:
   m=types.ModuleType(name);m.__path__=[str(path)];sys.modules[name]=m
  for name in ['vad_postprocessor','stream_vad_postprocessor']:
   spec=importlib.util.spec_from_file_location('fireredvad.core.'+name,root/'fireredvad/core'/(name+'.py'));m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m);setattr(self,name,m)
 def decision(self,x):
  start=time.perf_counter();f=frontend(gain(x),self.stats);p=self.non.run(None,{'feat':f[None]})[0].reshape(-1).tolist();pp=self.vad_postprocessor.VadPostprocessor(5,.5,20,2000,20,0,0);d=pp.process(p);self.calls+=1;self.seconds+=time.perf_counter()-start;return d
 def streaming(self,audio,threshold=.5):
  # Gain is causal and bounded on each 50ms capture frame, not on the future file.
  conditioned=np.concatenate([gain(audio[i:i+FRAME]) for i in range(0,len(audio),FRAME)]);f=frontend(conditioned,self.stats);cache=np.zeros((8,1,128,19),dtype=np.float32);pp=self.stream_vad_postprocessor.StreamVadPostprocessor(5,threshold,5,8,2000,20);at=0;active=False;flags=[];availability=[]
  for end in range(FRAME,len(audio)+FRAME,FRAME):
   end=min(end,len(audio));ready=max(0,min(len(f),1+(end-400)//160))
   if ready>at:
    p,cache=self.stream.run(None,{'feat':f[None,at:ready],'caches_in':cache});self.calls+=1
    for value in p.reshape(-1):
     result=pp.process_one_frame(float(value))
     if result.is_speech_start:active=True
     if result.is_speech_end:active=False
    at=ready
   flags.append(active);availability.append(end)
  return flags,availability
 def sliding(self,audio,window,delay):
  assert window>delay+.5 and delay>=0
  flags=[];availability=[];committed=0;span=int(window*SR);lag=int(delay*SR)
  ends=list(range(HOP,len(audio)+1,HOP))
  if not ends or ends[-1]!=len(audio):ends.append(len(audio))
  for end in ends:
   start=max(0,end-span);d=self.decision(audio[start:end]);limit=max(0,min(len(audio),((end-lag)//FRAME)*FRAME))
   while committed+FRAME<=limit:
    # A decision is made only after this complete delayed source frame is observed.
    left=max(0,math.floor((committed-start)/160));right=min(len(d),math.ceil((committed+FRAME-start)/160))
    flags.append(any(d[left:right]) if left<right else False);availability.append(end);committed+=FRAME
  return flags,availability

def replay(audio,flags,availability,directory,jar,stdlib):
 directory.mkdir(parents=True);binary=directory/'input.bin'
 with binary.open('wb') as f:
  for i,flag in enumerate(flags):
   pcm=audio[i*FRAME:(i+1)*FRAME];f.write(struct.pack('<I?',len(pcm),bool(flag)));f.write(pcm.astype('<f4').tobytes())
 subprocess.run(['java','-cp',str(jar)+':'+str(stdlib),'expo.modules.guardiannativeprotection.ReplayStudyKt',str(binary),str(directory/'windows')],check=True,stdout=subprocess.DEVNULL)
 raw=audio.astype('<f4').tobytes();out=[]
 # Technical rollover may omit a silence block at the fixed PCM cap. Match
 # original capture chunks, not an assumed single contiguous source interval.
 lookup={}
 for i in range(0,len(audio),FRAME):lookup.setdefault(audio[i:i+FRAME].astype('<f4').tobytes(),[]).append(i)

 for event in csv.DictReader((directory/'windows/events.tsv').open(),delimiter='\t'):
  p=directory/'windows'/('segment-'+event['id']+'.f32');x=np.fromfile(p,dtype='<f4');index=math.ceil(int(event['availableAtSample'])/FRAME)-1
  previous=-1;ranges=[]
  for j in range(0,len(x),FRAME):
   block=x[j:j+FRAME];candidates=lookup.get(block.tobytes(),[])
   if len(block)<FRAME:candidates=[i for i in range(0,len(audio),FRAME) if np.array_equal(audio[i:i+len(block)],block)]
   candidates=[i for i in candidates if i>=previous and i+len(block)<=int(event['availableAtSample'])]
   assert candidates,'Window contains altered or future samples'
   start=candidates[0];end=start+len(block)
   if ranges and ranges[-1][1]==start:ranges[-1][1]=end
   else:ranges.append([start,end])
   previous=end
  out.append(dict(path=str(p),startSample=ranges[0][0],endSample=ranges[-1][1],sourceRanges=ranges,availableSeconds=availability[index]/SR,reason=event['reason']))
 return out

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ['manifest','output','model-root','segmenter-jar','kotlin-stdlib','module']:p.add_argument('--'+key,type=Path,required=True)
 p.add_argument('--strategies',nargs='+',choices=['current','stream0.5','sliding4-lag0','sliding4-lag1.6','sliding8-lag1.6'],default=['current','stream0.5','sliding4-lag0','sliding4-lag1.6','sliding8-lag1.6']);p.add_argument('--voice-stress',action='store_true');p.add_argument('--strategy-suffix',default='');a=p.parse_args()
 if a.output.exists():p.error('Use a new output directory')
 a.output.mkdir(parents=True);m=Models(a.model_root);sys.path.insert(0,str(a.module/'tests'));import replay_v32
 # Explicit production parameters: upstream Python helper otherwise defaults
 # max_speech_duration to 20s, whereas GuardianNeuralVad uses 30s.
 import sherpa_onnx as sherpa
 def production_detector():
  c=sherpa.VadModelConfig();c.silero_vad.model=str(a.module/'android/src/main/assets/silero_vad.onnx');c.silero_vad.threshold=.5;c.silero_vad.min_speech_duration=.192;c.silero_vad.min_silence_duration=.128;c.silero_vad.max_speech_duration=30;c.silero_vad.window_size=512;c.sample_rate=SR;c.num_threads=1
  return sherpa.VoiceActivityDetector(c,buffer_size_in_seconds=40)
 replay_v32.detector=production_detector
 current_flags=replay_v32.flags
 rows=json.loads(a.manifest.read_text());results=[]
 for row in rows:
  original=read(Path(row['path']));variants=[(0,0)]
  if a.voice_stress and row['label']=='recorded-voice':variants += [(-6,0),(-6,5)]
  for db,offset in variants:
   audio=original[offset*16:]*10**(db/20)
   for strategy in a.strategies:
    label=row['id']+'-db'+str(db)+'-offset'+str(offset);directory=a.output/label/strategy;calls=m.calls;model_seconds=m.seconds;begin=time.perf_counter()
    if strategy=='current':flags=current_flags(audio);avail=[min((i+1)*FRAME,len(audio)) for i in range(len(flags))]
    elif strategy.startswith('stream'):flags,avail=m.streaming(audio,float(strategy[6:]))
    else:
     window,lag=strategy.removeprefix('sliding').split('-lag');flags,avail=m.sliding(audio,float(window),float(lag))
    detector_wall=time.perf_counter()-begin;windows=replay(audio,flags,avail,directory,a.segmenter_jar,a.kotlin_stdlib);confirmation_start=time.perf_counter()
    for w in windows:
     x=np.fromfile(w['path'],dtype='<f4');w['accepted']=any(m.decision(x)) if strategy=='current' else True
    confirm_wall=time.perf_counter()-confirmation_start
    record=dict(inputId=row['id'],label=row['label'],expected=row.get('expected'),expectedRepetitions=row.get('expectedRepetitions'),db=db,offsetMs=offset,strategy=strategy+a.strategy_suffix,inputSeconds=len(audio)/SR,processedSeconds=sum(min(FRAME,len(audio)-i*FRAME) for i in range(len(flags)))/SR,detectorWallSeconds=detector_wall,confirmationWallSeconds=confirm_wall,detectorCalls=m.calls-calls,positiveFrames=sum(flags),windows=windows)
    results.append(record);(a.output/'results-private.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print(row['id'],db,offset,strategy,'windows',len(windows),'accepted',sum(w['accepted'] for w in windows),'detector',round(detector_wall,3),flush=True)
if __name__=='__main__':main()
