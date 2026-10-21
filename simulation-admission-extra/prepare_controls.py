from pathlib import Path
import importlib.util,json,argparse
import numpy as np
from scipy.signal import butter,sosfilt
p=argparse.ArgumentParser(description='Generate additional synthetic noise and noisy copies; not independent real recordings');p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
if args.output.exists():p.error('Choose a new output directory')
repo=Path(__file__).resolve().parents[1];spec=importlib.util.spec_from_file_location('base_eval',repo/'simulation-admission/evaluate.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
r=args.output;r.mkdir(parents=True);manifest=[];rng=np.random.default_rng(20261021);n=8*16000;t=np.arange(n)/16000;white=rng.standard_normal(n);f=np.fft.rfftfreq(n,1/16000);pink=np.fft.irfft(np.fft.rfft(white)/np.sqrt(np.maximum(f,1)),n=n);wind=sosfilt(butter(3,250,fs=16000,output='sos'),white);fan=np.sin(2*np.pi*60*t)+.5*np.sin(2*np.pi*120*t)+.25*np.sin(2*np.pi*240*t)+.2*white;beeps=np.sin(2*np.pi*600*t)*(np.mod(t,1.2)<.2);clicks=np.zeros(n)
for pos in rng.integers(0,n-800,12):clicks[pos:pos+800]+=np.exp(-np.arange(800)/100)*rng.standard_normal(800)
for name,audio in [('white',white),('pink',pink),('wind',wind),('fan',fan),('beeps',beeps),('clicks',clicks)]:
 for db in [-65,-45,-25]:
  x=np.clip(audio/max(np.sqrt(np.mean(audio*audio)),1e-12)*10**(db/20),-.98,.98).astype('<f4');p=r/f'{name}-{db}.f32';x.tofile(p);manifest.append(dict(id=p.stem,label='synthetic-noise',condition=name,path=str(p)))
original=json.loads(args.manifest.read_text())
for row in original:
 if row['label']=='negative':continue
 audio=base.read(Path(row['path']));noise=rng.standard_normal(len(audio));freq=np.fft.rfftfreq(len(audio),1/16000);noise=np.fft.irfft(np.fft.rfft(noise)/np.sqrt(np.maximum(freq,1)),n=len(audio));noise/=max(np.sqrt(np.mean(noise*noise)),1e-12)
 for snr in ([20,10] if row['label']=='positive' else [10]):
  mixed=np.clip(audio+noise*np.sqrt(np.mean(audio*audio))/10**(snr/20),-.98,.98).astype('<f4');p=r/(row['id']+f'-snr{snr}.f32');mixed.tofile(p);manifest.append(dict(id=p.stem,label='speech-noisy' if row['label']=='positive' else 'synthetic-word-noisy',condition=row['condition'],path=str(p)))
(r/'manifest-private.json').write_text(json.dumps(manifest,indent=2));print('New controls',len(manifest))
