"""Prepare fixed, actual phone windows. All outputs remain private.
Usage: --inputs JSON (id, root per recording) --output DIR --tools DIR.
No ASR, no oracle choice, no model state reset between words for continuous methods.
"""
import argparse,json,sys,time
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'etude-bruit-elargie'))
from prepare import apply,stft,istft

def ulunas(x,tools):
 import onnxruntime as ort
 opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1
 model=tools/'ul-unas/ulunas_onnx/onnx_models/ulunas_stream_simple.onnx'
 session=ort.InferenceSession(str(model),opts,providers=['CPUExecutionProvider'])
 z=stft(x);cache={i.name:np.zeros(i.shape,dtype=np.float32) for i in session.get_inputs() if i.name!='mix'};out=[]
 for frame in z:
  v=np.stack([frame.real,frame.imag],axis=-1)[None,:,None,:].astype(np.float32)
  enhanced,*states=session.run(None,dict(mix=v,**cache));cache=dict(zip(cache,states));out.append(enhanced[0,:,0,0]+1j*enhanced[0,:,0,1])
 return istft(np.asarray(out),len(x))

def configs():
 cs=[dict(name='original',family='reference')]
 cs += [dict(name=f'gain{g}',family='gain',segmented=True,gain=g) for g in [2,4,8]]
 cs += [dict(name=f'hp{f}',family='highpass',hz=f) for f in [100,150,300,450]]
 cs += [dict(name=f'wiener-o{o}-f{f}',family='wiener',over=o,floor=f) for o in [.5,1,2] for f in [.25,.5,.75]]
 cs += [dict(name=f'dd-a{a}-f{f}',family='dd',alpha=a,floor=f) for a in [.92,.98] for f in [.15,.4]]
 cs += [dict(name='notch3',family='notch')]
 cs += [dict(name=f'speex{db}',family='speex',db=db) for db in [-6,-12,-24,-36]]
 cs += [dict(name='rnnoise-v01',family='rnnoise'),dict(name='rnnoise-mix50',family='rnnoise',blend=.5)]
 cs += [dict(name=f'phone-gtcrn-mix{b}',family='phone-gtcrn',blend=b) for b in [1,.25,.5,.75]]
 cs += [dict(name=f'df3-g{g}-a{a}',family='df',segmented=True,gain=g,atten=a) for g,a in [(8,6),(8,9),(8,12),(16,18),(16,24)]]
 cs += [dict(name='df3ll-g16-a12',family='df',segmented=True,gain=16,atten=12,model='3ll')]
 cs += [dict(name='ulunas',family='ulunas'),dict(name='ulunas-mix50',family='ulunas',blend=.5)]
 return cs

def main():
 p=argparse.ArgumentParser();p.add_argument('--inputs',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--tools',type=Path,required=True);a=p.parse_args()
 if a.output.exists():p.error('Use a fresh output directory')
 a.output.mkdir(parents=True);records=[];metrics=[];failures=[];baseline=[]
 cs=configs();(a.output/'configs.json').write_text(json.dumps(cs,indent=2))
 for dataset in json.loads(a.inputs.read_text()):
  r=Path(dataset['root']);id=dataset['id'];events=json.loads((r/'events-private.json').read_text());x=np.fromfile(next((r/'wav').glob('*-asr.f32')),'<f4');clean=np.fromfile(next((r/'continuous-study').glob('*-asr.f32')),'<f4')
  plan=json.loads((r/'test-plan.json').read_text(encoding='utf-8-sig'))['prompts'];speech=[e for e in events if e['event']=='CONTINUOUS_STUDY_WINDOW' and (e['originalAccepted'] or e['enhancedAccepted'])]
  # The first recording includes one explicitly confirmed silence false positive.
  if dataset.get('negativeSegments'):speech=[e for e in speech if e['segment'] not in dataset['negativeSegments']]
  assert len(speech)==len(plan)==3
  labels={e['segment']:plan[i]['text'] for i,e in enumerate(speech)}
  for c in cs:
   folder=a.output/id/c['name'];folder.mkdir(parents=True);start=time.perf_counter();full=None
   try:
    if not c.get('segmented'):
     if c['family']=='phone-gtcrn':
      full=x[:len(clean)]*(1-c['blend'])+clean*c['blend']
     elif c['family']=='ulunas':
      if c.get('blend',1)<1:full=ul_cache*c['blend']+x*(1-c['blend'])
      else:full=ulunas(x,a.tools);ul_cache=full
     else:full=apply(x,c,a.tools,folder)
     assert np.isfinite(full).all();full.astype('<f4').tofile(folder/'full.f32')
    for e in events:
     if e['event']!='CONTINUOUS_STUDY_WINDOW':continue
     begin,end=e['startSample'],e['endSample'];piece=folder/f"segment-{e['segment']}.f32";raw=x[begin:end]
     if full is not None:y=full[begin:end]
     elif c['family']=='gain':y=raw*max(1,min(c['gain'],.8/max(float(np.max(abs(raw))),1e-20)))
     else:
      sub=folder/f"segment-{e['segment']}";sub.mkdir();y=apply(raw,c,a.tools,sub)
     assert len(y)==len(raw) and np.isfinite(y).all();y.astype('<f4').tofile(piece)
     row=dict(id=f"{id}-{c['name']}-{e['segment']}",path=str(piece),dataset=id,method=c['name'],family=c['family'],segment=e['segment'],expected=labels.get(e['segment'],''),rawAccepted=e['originalAccepted'],phoneCleanAccepted=e['enhancedAccepted'],startSample=begin,endSample=end,group='speech' if e['segment'] in labels else 'negative')
     records.append(row)
     if c['name'] in ['original','phone-gtcrn-mix1']:baseline.append(row)
    metrics.append(dict(dataset=id,method=c['name'],seconds=time.perf_counter()-start,peak=float(np.max(abs(full))) if full is not None else None))
    print(id,c['name'],'ready',len(records),flush=True)
   except Exception as error:
    failures.append(dict(dataset=id,method=c['name'],error=str(error)));print('FAILED',id,c['name'],str(error),flush=True)
   (a.output/'manifest-private.json').write_text(json.dumps(records,ensure_ascii=False,indent=2));(a.output/'baseline-private.json').write_text(json.dumps(baseline,ensure_ascii=False,indent=2));(a.output/'metrics.json').write_text(json.dumps(metrics,indent=2));(a.output/'failures.json').write_text(json.dumps(failures,indent=2))
if __name__=='__main__':main()
